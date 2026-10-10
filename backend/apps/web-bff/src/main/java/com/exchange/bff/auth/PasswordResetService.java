package com.exchange.bff.auth;

import com.exchange.bff.common.errors.BffHttpErrorCode;
import com.exchange.bff.common.mail.Mailer;
import com.exchange.common.crypto.Sha256;
import com.exchange.core.api.exception.ApiException;
import com.exchange.user.audit.AuthEventType;
import com.exchange.user.audit.AuthOutcome;
import com.exchange.user.audit.UserAuthEvent;
import com.exchange.user.audit.UserAuthEventRepository;
import com.exchange.user.identity.User;
import com.exchange.user.identity.UserRepository;
import com.exchange.user.identity.UserStatus;
import com.exchange.user.mfa.MfaService;
import com.exchange.user.password.UserPasswordResetCode;
import com.exchange.user.password.UserPasswordResetCodeRepository;
import com.exchange.user.session.SessionRevokedReason;
import com.exchange.user.session.UserSessionRepository;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Setting a new password after forgetting the old one.
 *
 * <p>Unlike email verification, this cannot be session-bound -- someone resetting a
 * password is logged out by definition. So the address and the code travel together, and
 * the code is the only proof that whoever is asking holds the inbox.
 */
@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    private static final Duration TTL = Duration.ofMinutes(15);

    /**
     * Same 60 seconds as email verification, but enforced <b>silently</b>.
     *
     * <p>This endpoint answers identically for an address with no account, so it cannot
     * report a cooldown: a 429 would mean "this address is registered and asked recently"
     * and a 204 would mean "unknown", which is the enumeration oracle the whole flow is
     * shaped to avoid. The caller sees success and simply gets no second email.
     */
    private static final Duration RESEND_COOLDOWN = Duration.ofSeconds(60);
    private static final int CODE_BOUND = 1_000_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository users;
    private final UserPasswordResetCodeRepository codes;
    private final UserSessionRepository sessions;
    private final UserAuthEventRepository authEvents;
    private final PasswordEncoder passwordEncoder;
    private final MfaService mfa;
    private final Mailer mailer;

    public PasswordResetService(UserRepository users, UserPasswordResetCodeRepository codes,
                                UserSessionRepository sessions,
                                UserAuthEventRepository authEvents,
                                PasswordEncoder passwordEncoder, MfaService mfa,
                                Mailer mailer) {
        this.users = users;
        this.codes = codes;
        this.sessions = sessions;
        this.authEvents = authEvents;
        this.passwordEncoder = passwordEncoder;
        this.mfa = mfa;
        this.mailer = mailer;
    }

    /**
     * Emails a code, if that address has an account.
     *
     * <p>Says nothing either way. Answering differently for a registered address would
     * make this a way to test which emails have an account here.
     */
    @Transactional
    public void request(String email, String ipAddress) {
        Optional<User> found = users.findByEmail(email);
        if (found.isEmpty()) {
            log.debug("password reset requested for an address with no account");
            return;
        }
        User user = found.get();
        if (!user.canLogIn()) {
            // A suspended or closed account should not be recoverable by whoever holds
            // the inbox -- reinstating it is a decision, not a self-service action. Nor a
            // sub-account: its master holds the inbox, and a sub never gets a password.
            log.info("password reset refused for a {} account", user.getStatus());
            return;
        }

        Optional<UserPasswordResetCode> live = codes.findByUserIdAndUsedAtIsNull(user.getId());
        if (live.filter(c -> c.getCreatedAt() != null)
                .filter(c -> c.getCreatedAt().isAfter(Instant.now().minus(RESEND_COOLDOWN)))
                .isPresent()) {
            log.debug("password reset requested again within the cooldown");
            return;
        }

        // One live code at a time: every extra one is another million-to-one draw.
        codes.deleteByUserIdAndUsedAtIsNull(user.getId());

        String code = String.format("%06d", RANDOM.nextInt(CODE_BOUND));
        codes.save(new UserPasswordResetCode(user.getId(), Sha256.of(code), user.getEmail(),
                Instant.now().plus(TTL)));

        mailer.send(user.getEmail(), "Your password reset code", body(code));
        record(user.getId(), email, AuthEventType.PASSWORD_RESET_REQUESTED,
                AuthOutcome.SUCCESS, ipAddress, null);
    }

    /**
     * Sets the new password.
     *
     * <p><b>An account with a second factor must still produce a code.</b> Otherwise
     * whoever compromises the mailbox owns the account outright, and 2FA -- which exists
     * precisely to survive that -- is bypassed by the recovery flow it was meant to be
     * protected by. A recovery code works here too, which is what covers a lost
     * authenticator.
     *
     * <p>Every session is revoked afterwards. If an attacker was already inside, the reset
     * has to end that, not merely change what they would need to get back in.
     *
     * @throws ApiException {@code INVALID_RESET_CODE} for anything wrong with the code --
     *         unknown, expired, spent, exhausted, or for a different address
     * @throws com.exchange.user.mfa.exception.InvalidMfaCodeException the second factor
     *         did not check out
     */
    @Transactional(noRollbackFor = ApiException.class)
    public void confirm(String email, String code, String newPassword, String mfaCode,
                        String ipAddress) {
        User user = users.findByEmail(email)
                .filter(User::canLogIn)
                .orElseThrow(() -> new ApiException(BffHttpErrorCode.INVALID_RESET_CODE));

        UserPasswordResetCode pending = codes.findByUserIdAndUsedAtIsNull(user.getId())
                .orElseThrow(() -> new ApiException(BffHttpErrorCode.INVALID_RESET_CODE));

        if (!pending.isRedeemableFor(user.getEmail(), Instant.now())) {
            throw new ApiException(BffHttpErrorCode.INVALID_RESET_CODE);
        }
        if (!MessageDigest.isEqual(pending.getCodeHash(), Sha256.of(code))) {
            // noRollbackFor above is what lets this survive the throw. Without it the
            // increment is rolled back and the cap silently does nothing.
            pending.recordFailedAttempt();
            codes.save(pending);
            record(user.getId(), email, AuthEventType.PASSWORD_RESET_COMPLETED,
                    AuthOutcome.FAILURE, ipAddress, "BAD_CODE");
            throw new ApiException(BffHttpErrorCode.INVALID_RESET_CODE);
        }

        // Proving inbox access is not proving possession of the factor.
        if (user.isMfaEnabled()) {
            mfa.challenge(user.getId(), mfaCode, ipAddress);
        }

        pending.setUsedAt(Instant.now());
        codes.save(pending);

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        users.save(user);

        sessions.revokeAllForUser(user.getId(), SessionRevokedReason.PASSWORD_CHANGED,
                Instant.now());
        record(user.getId(), email, AuthEventType.PASSWORD_RESET_COMPLETED,
                AuthOutcome.SUCCESS, ipAddress, null);
    }

    private String body(String code) {
        return "Your password reset code is:\n\n    " + code
                + "\n\nIt expires in " + TTL.toMinutes() + " minutes.\n\n"
                + "We will never send you a link to click or ask you for this code. "
                + "If you did not request a password reset, ignore this message -- your "
                + "password has not changed.";
    }

    private void record(Long userId, String email, AuthEventType type, AuthOutcome outcome,
                        String ipAddress, String failureReason) {
        authEvents.save(UserAuthEvent.builder()
                .userId(userId)
                .emailAttempted(email)
                .eventType(type)
                .outcome(outcome)
                .failureReason(failureReason)
                .ipAddress(ipAddress)
                .build());
    }
}
