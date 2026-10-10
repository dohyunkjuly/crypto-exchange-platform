package com.exchange.bff.auth;

import com.exchange.bff.auth.dto.LoginOutcome;
import com.exchange.bff.auth.google.GoogleIdentity;
import com.exchange.bff.auth.google.GoogleTokenVerifier;
import com.exchange.bff.common.errors.BffHttpErrorCode;
import com.exchange.core.api.exception.ApiException;
import com.exchange.user.audit.AuthEventType;
import com.exchange.user.audit.AuthOutcome;
import com.exchange.user.audit.UserAuthEvent;
import com.exchange.user.audit.UserAuthEventRepository;
import com.exchange.user.identity.User;
import com.exchange.user.identity.UserRepository;
import com.exchange.user.identity.UserStatus;
import com.exchange.user.identity.external.ExternalProvider;
import com.exchange.user.identity.external.UserExternalIdentity;
import com.exchange.user.identity.external.UserExternalIdentityRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Signing in with Google.
 *
 * <p>The browser does the whole exchange with Google and ends up holding an ID token; this
 * verifies it and turns it into a session. Nothing here trusts the client beyond that
 * token, and the token is only worth anything because its signature, issuer and audience
 * were checked -- see {@code NimbusGoogleTokenVerifier}.
 */
@Service
public class GoogleAuthService {

    private final ObjectProvider<GoogleTokenVerifier> verifier;
    private final UserRepository users;
    private final UserExternalIdentityRepository identities;
    private final UserAuthEventRepository authEvents;
    private final LoginChallengeStore challenges;
    private final AuthService authService;
    private final EmailVerificationService emailVerification;
    private final PasswordEncoder passwordEncoder;

    public GoogleAuthService(ObjectProvider<GoogleTokenVerifier> verifier,
                             UserRepository users,
                             UserExternalIdentityRepository identities,
                             UserAuthEventRepository authEvents,
                             LoginChallengeStore challenges,
                             AuthService authService,
                             EmailVerificationService emailVerification,
                             PasswordEncoder passwordEncoder) {
        this.verifier = verifier;
        this.users = users;
        this.identities = identities;
        this.authEvents = authEvents;
        this.challenges = challenges;
        this.authService = authService;
        this.emailVerification = emailVerification;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Signs in, linking or creating an account as needed.
     *
     * <p><b>An account with a second factor is still challenged.</b> Skipping it because
     * Google vouched for the person would make 2FA bypassable by whoever controls their
     * Google account -- which is precisely the thing a second factor exists to survive.
     *
     * @throws ApiException {@code GOOGLE_SIGN_IN_UNAVAILABLE} if no client id is
     *         configured, {@code INVALID_GOOGLE_TOKEN} if the token does not verify,
     *         {@code ACCOUNT_EXISTS_USE_PASSWORD} if the address belongs to an account
     *         this token is not allowed to claim, or {@code INVALID_CREDENTIALS} if the
     *         account is not active
     */
    @Transactional
    public LoginOutcome signIn(String idToken, String ipAddress, String userAgent) {
        GoogleTokenVerifier tokenVerifier = verifier.getIfAvailable();
        if (tokenVerifier == null) {
            throw new ApiException(BffHttpErrorCode.GOOGLE_SIGN_IN_UNAVAILABLE);
        }
        GoogleIdentity identity = tokenVerifier.verify(idToken);

        User user = identities
                .findByProviderAndSubject(ExternalProvider.GOOGLE, identity.subject())
                .map(link -> {
                    link.setLastLoginAt(Instant.now());
                    identities.save(link);
                    return users.findById(link.getUserId()).orElseThrow(
                            () -> new ApiException(BffHttpErrorCode.INVALID_CREDENTIALS));
                })
                .orElseGet(() -> linkOrCreate(identity, ipAddress));

        if (!user.canLogIn()) {
            recordFailure(user.getId(), identity.email(), ipAddress,
                    "ACCOUNT_" + user.getStatus());
            throw new ApiException(BffHttpErrorCode.INVALID_CREDENTIALS);
        }

        // Google proved who they are. It did not prove they hold the second factor.
        if (user.isMfaEnabled()) {
            return LoginOutcome.mfaRequired(challenges.issue(user.getId()));
        }
        return LoginOutcome.authenticated(
                authService.mintSession(user, user.getEmail(), ipAddress, userAgent));
    }

    /** What a settings screen needs to decide between a "Link Google" and an "Unlink"
     *  button. Reads the link table rather than the user row: nothing is denormalised onto
     *  the user, and this is not a hot path. */
    @Transactional(readOnly = true)
    public List<UserExternalIdentity> linkedIdentities(Long userId) {
        return identities.findByUserId(userId);
    }

    /**
     * Detaches Google from the account.
     *
     * <p><b>Refused when the account has no password</b>, which is every account Google
     * itself created ({@code new User(email, null)}). For those, Google is the only way in,
     * and unlinking it would leave the user holding nothing -- recoverable only through
     * password reset, which is an odd thing to force someone through to undo a button they
     * were allowed to press. They are told to set a password first instead.
     *
     * <p>The password is also <b>required as proof</b>, and that is the less obvious half.
     * A password merely existing says the column is populated, not that the user remembers
     * it; someone who set one years ago and has signed in with Google ever since would
     * otherwise pass the guard above and still end up locked out. Making them type it is
     * the only way to check the credential they are falling back on actually works.
     *
     * <p>Note what is deliberately <i>not</i> claimed: this is not protection against a
     * stolen session. A thief gains nothing by unlinking -- they cannot attach their own
     * Google account in its place, because {@link #linkOrCreate} only ever links an
     * identity to an account with the same address.
     *
     * @throws ApiException {@code GOOGLE_NOT_LINKED} if there is nothing linked,
     *         {@code PASSWORD_REQUIRED_BEFORE_UNLINK} if the account has no password, or
     *         {@code INVALID_CREDENTIALS} if the password does not match
     */
    @Transactional
    public void unlinkGoogle(User user, String rawPassword, String ipAddress) {
        UserExternalIdentity link = identities
                .findByUserIdAndProvider(user.getId(), ExternalProvider.GOOGLE)
                .orElseThrow(() -> new ApiException(BffHttpErrorCode.GOOGLE_NOT_LINKED));

        if (user.getPasswordHash() == null) {
            throw new ApiException(BffHttpErrorCode.PASSWORD_REQUIRED_BEFORE_UNLINK);
        }
        if (rawPassword == null
                || !passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            recordFailure(user.getId(), user.getEmail(), ipAddress, "UNLINK_BAD_PASSWORD");
            throw new ApiException(BffHttpErrorCode.INVALID_CREDENTIALS);
        }

        identities.delete(link);
        authEvents.save(UserAuthEvent.builder()
                .userId(user.getId())
                .emailAttempted(user.getEmail())
                .eventType(AuthEventType.EXTERNAL_IDENTITY_UNLINKED)
                .outcome(AuthOutcome.SUCCESS)
                .ipAddress(ipAddress)
                .metadata("{\"provider\":\"GOOGLE\"}")
                .build());
    }

    /**
     * First sign-in with this Google account: either attach it to an existing account, or
     * make a new one.
     *
     * <p>The linking rule is the security decision in this whole feature. Attaching a
     * Google identity to an existing account on a matching email alone is a known
     * account-takeover: an attacker registers {@code victim@gmail.com} with a password,
     * waits, and the moment the real owner clicks "Sign in with Google" their identity is
     * absorbed into the attacker's account -- which the attacker still has the password to.
     *
     * <p>So linking needs proof from <b>both</b> sides that the address is genuinely
     * theirs: Google must say the address is verified, and our account must already have
     * been verified through our own emailed code. Either one missing and the link is
     * refused, with the user sent to sign in by password first.
     *
     * <p>The cost, stated plainly: someone whose address was pre-registered by an attacker
     * who never verified it cannot sign in with Google, and cannot take the address back
     * either -- account recovery does not exist yet. That path needs a password reset,
     * which is the missing piece rather than a reason to weaken this rule.
     */
    private User linkOrCreate(GoogleIdentity identity, String ipAddress) {
        if (identity.email() == null || identity.email().isBlank()) {
            throw new ApiException(BffHttpErrorCode.INVALID_GOOGLE_TOKEN);
        }
        Optional<User> existing = users.findByEmail(identity.email());

        if (existing.isPresent()) {
            User account = existing.get();
            // A sub-account's address is virtual and reaches nobody, so no token can prove it:
            // a link attempt on one is refused like any other unverified address.
            if (!identity.emailVerified() || !account.isEmailVerified() || !account.canLogIn()) {
                recordFailure(account.getId(), identity.email(), ipAddress,
                        "GOOGLE_LINK_REFUSED");
                throw new ApiException(BffHttpErrorCode.ACCOUNT_EXISTS_USE_PASSWORD);
            }
            link(account, identity);
            return account;
        }

        User created = new User(identity.email(), null);
        // Google's own verification stands in for ours, so a new user is not asked to
        // confirm an address Google already confirmed.
        created.setEmailVerified(identity.emailVerified());
        created = users.save(created);

        authEvents.save(UserAuthEvent.builder()
                .userId(created.getId())
                .emailAttempted(identity.email())
                .eventType(AuthEventType.REGISTERED)
                .outcome(AuthOutcome.SUCCESS)
                .ipAddress(ipAddress)
                .metadata("{\"provider\":\"GOOGLE\"}")
                .build());

        link(created, identity);

        // Rare -- consumer Gmail is always verified -- but real for some Workspace
        // configurations. Without this the account is created unverified and NOTHING ever
        // arrives: the password signup path sends a code, this one did not, so the user
        // would be told to confirm an address with an empty inbox and no obvious way to
        // trigger one.
        if (!created.isEmailVerified()) {
            emailVerification.send(created, ipAddress);
        }
        return created;
    }

    private void link(User user, GoogleIdentity identity) {
        UserExternalIdentity link = new UserExternalIdentity(user.getId(),
                ExternalProvider.GOOGLE, identity.subject(), identity.email());
        link.setLastLoginAt(Instant.now());
        identities.save(link);

        // Recorded on every path, including the one that just wrote REGISTERED: that event
        // says an account appeared, not which identity now opens it.
        authEvents.save(UserAuthEvent.builder()
                .userId(user.getId())
                .emailAttempted(identity.email())
                .eventType(AuthEventType.EXTERNAL_IDENTITY_LINKED)
                .outcome(AuthOutcome.SUCCESS)
                .metadata("{\"provider\":\"GOOGLE\"}")
                .build());
    }

    private void recordFailure(Long userId, String email, String ipAddress, String reason) {
        authEvents.save(UserAuthEvent.builder()
                .userId(userId)
                .emailAttempted(email)
                .eventType(AuthEventType.LOGIN_FAILED)
                .outcome(AuthOutcome.FAILURE)
                .failureReason(reason)
                .ipAddress(ipAddress)
                .build());
    }
}
