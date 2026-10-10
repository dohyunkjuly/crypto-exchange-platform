package com.exchange.bff.auth;

import com.exchange.bff.auth.dto.LoginOutcome;
import com.exchange.bff.auth.dto.LoginResult;
import com.exchange.bff.common.errors.BffHttpErrorCode;
import com.exchange.core.api.exception.ApiException;
import com.exchange.user.audit.AuthEventType;
import com.exchange.user.audit.AuthOutcome;
import com.exchange.user.audit.UserAuthEvent;
import com.exchange.user.audit.UserAuthEventRepository;
import com.exchange.user.identity.User;
import com.exchange.user.identity.UserRepository;
import com.exchange.user.mfa.MfaService;
import com.exchange.user.identity.UserStatus;
import com.exchange.user.identity.UserType;
import com.exchange.user.session.SessionRevokedReason;
import com.exchange.user.session.UserSession;
import com.exchange.user.session.UserSessionRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Register, log in, log out. web-bff's own -- open-api has no login flow at all (a bot
 * authenticates with a signed API key, not a session), so this has exactly one caller and
 * belongs here rather than in libs/domain/user alongside the entities/repositories it
 * fronts.
 */
@Service
public class AuthService {

    /** How long a session lives from creation, regardless of activity. No sliding window
     *  in this pass. */
    private static final Duration SESSION_TTL = Duration.ofDays(30);

    private static final Duration LOGIN_FAILURE_WINDOW = Duration.ofMinutes(15);
    private static final long MAX_FAILURES_PER_EMAIL = 5;
    private static final long MAX_FAILURES_PER_IP = 20;

    private final UserRepository users;
    private final UserSessionRepository sessions;
    private final UserAuthEventRepository authEvents;
    private final PasswordEncoder passwordEncoder;
    private final MfaService mfa;
    private final LoginChallengeStore challenges;
    private final EmailVerificationService emailVerification;

    public AuthService(UserRepository users, UserSessionRepository sessions,
                       UserAuthEventRepository authEvents, PasswordEncoder passwordEncoder,
                       MfaService mfa, LoginChallengeStore challenges,
                       EmailVerificationService emailVerification) {
        this.users = users;
        this.sessions = sessions;
        this.authEvents = authEvents;
        this.passwordEncoder = passwordEncoder;
        this.mfa = mfa;
        this.challenges = challenges;
        this.emailVerification = emailVerification;
    }

    /** @throws ApiException {@link BffHttpErrorCode#EMAIL_ALREADY_REGISTERED} */
    @Transactional
    public User register(String email, String rawPassword, String name, String ipAddress) {
        if (users.existsByEmail(email)) {
            throw new ApiException(BffHttpErrorCode.EMAIL_ALREADY_REGISTERED);
        }
        User user = new User(email, passwordEncoder.encode(rawPassword));
        user.setName(name);
        user = users.save(user);

        authEvents.save(UserAuthEvent.builder()
                .userId(user.getId())
                .emailAttempted(email)
                .eventType(AuthEventType.REGISTERED)
                .outcome(AuthOutcome.SUCCESS)
                .ipAddress(ipAddress)
                .build());

        // In the same transaction as the account: if the mail cannot be handed off, the
        // registration rolls back rather than leaving someone with an account they can
        // never verify and an address they cannot re-register.
        emailVerification.send(user, ipAddress);
        return user;
    }

    /**
     * @throws ApiException {@link BffHttpErrorCode#TOO_MANY_ATTEMPTS} too many recent failures
     *         against this email or IP; {@link BffHttpErrorCode#INVALID_CREDENTIALS} wrong
     *         password, unknown email, or a non-active account -- deliberately the same
     *         code for all three, so the response can't be used to enumerate registered
     *         emails or account status
     */
    @Transactional
    public LoginOutcome login(String email, String rawPassword, String ipAddress,
                             String userAgent) {
        Instant now = Instant.now();
        Instant windowStart = now.minus(LOGIN_FAILURE_WINDOW);

        // LOGIN_FAILED specifically, not every failure. Counting all of them meant a
        // flow anyone can trigger by knowing an address -- five wrong password-reset
        // codes -- locked that account out of logging in for fifteen minutes.
        long emailFailures = authEvents.countByEmailAttemptedAndEventTypeAndOutcomeAndCreatedAtAfter(
                email, AuthEventType.LOGIN_FAILED, AuthOutcome.FAILURE, windowStart);
        long ipFailures = authEvents.countByIpAddressAndEventTypeAndOutcomeAndCreatedAtAfter(
                ipAddress, AuthEventType.LOGIN_FAILED, AuthOutcome.FAILURE, windowStart);
        if (emailFailures >= MAX_FAILURES_PER_EMAIL || ipFailures >= MAX_FAILURES_PER_IP) {
            recordFailure(null, email, ipAddress, "RATE_LIMITED");
            throw new ApiException(BffHttpErrorCode.TOO_MANY_ATTEMPTS);
        }

        Optional<User> maybeUser = users.findByEmail(email);
        if (maybeUser.isEmpty()) {
            recordFailure(null, email, ipAddress, "NO_SUCH_USER");
            throw new ApiException(BffHttpErrorCode.INVALID_CREDENTIALS);
        }

        User user = maybeUser.get();
        if (!user.canLogIn()) {
            recordFailure(user.getId(), email, ipAddress, user.getType() == UserType.SUB ? "SUB_ACCOUNT"
                    : "ACCOUNT_" + user.getStatus());
            throw new ApiException(BffHttpErrorCode.INVALID_CREDENTIALS);
        }
        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            recordFailure(user.getId(), email, ipAddress, "BAD_PASSWORD");
            throw new ApiException(BffHttpErrorCode.INVALID_CREDENTIALS);
        }

        // The password was right, which for an account with a second factor is now only
        // half the answer. No session exists until the code checks out, so a stolen
        // password on its own opens nothing -- including read access to balances.
        if (user.isMfaEnabled()) {
            return LoginOutcome.mfaRequired(challenges.issue(user.getId()));
        }
        return LoginOutcome.authenticated(mintSession(user, email, ipAddress, userAgent));
    }

    /**
     * The second half of a login: the code that turns a challenge into a session.
     *
     * @throws ApiException            the challenge is unknown, expired, or was burned by
     *                                 too many wrong codes
     * @throws com.exchange.user.mfa.exception.InvalidMfaCodeException the code is wrong,
     *                                 expired, or already spent
     */
    @Transactional
    public LoginResult completeMfaLogin(String challengeToken, String code, String ipAddress,
                                        String userAgent) {
        Long userId = challenges.resolve(challengeToken)
                .orElseThrow(() -> new ApiException(BffHttpErrorCode.MFA_CHALLENGE_EXPIRED));

        try {
            mfa.challenge(userId, code, ipAddress);
        } catch (RuntimeException e) {
            // Counts toward the cap, so a challenge is not an unlimited guessing window
            // against six digits.
            challenges.registerFailure(challengeToken);
            throw e;
        }
        // Burned on success, so one challenge can never mint two sessions.
        challenges.invalidate(challengeToken);

        User user = users.findById(userId).orElseThrow(
                () -> new ApiException(BffHttpErrorCode.INVALID_CREDENTIALS));
        return mintSession(user, user.getEmail(), ipAddress, userAgent);
    }

    /** Package-private so {@code GoogleAuthService} mints sessions the same way, with the
     *  same LOGIN_SUCCEEDED audit trail, rather than growing a second implementation that
     *  drifts. */
    LoginResult mintSession(User user, String email, String ipAddress, String userAgent) {
        SessionToken token = SessionToken.generate();
        UserSession session = new UserSession(user.getId(), token.hash(), ipAddress,
                Instant.now().plus(SESSION_TTL));
        session.setUserAgent(userAgent);
        session = sessions.save(session);

        authEvents.save(UserAuthEvent.builder()
                .userId(user.getId())
                .emailAttempted(email)
                .sessionId(session.getId())
                .eventType(AuthEventType.LOGIN_SUCCEEDED)
                .outcome(AuthOutcome.SUCCESS)
                .ipAddress(ipAddress)
                .userAgent(userAgent)
                .build());

        return new LoginResult(user, token.raw(), session.getExpiresAt());
    }

    /** Idempotent: a missing or already-revoked token is not an error. */
    @Transactional
    public void logout(String rawSessionToken, String ipAddress) {
        sessions.findByTokenHash(SessionToken.hash(rawSessionToken)).ifPresent(session -> {
            session.revoke(SessionRevokedReason.LOGOUT, Instant.now());
            authEvents.save(UserAuthEvent.builder()
                    .userId(session.getUserId())
                    .sessionId(session.getId())
                    .eventType(AuthEventType.LOGOUT)
                    .outcome(AuthOutcome.SUCCESS)
                    .ipAddress(ipAddress)
                    .build());
        });
    }

    @Transactional
    public void logoutAll(Long userId, String ipAddress) {
        sessions.revokeAllForUser(userId, SessionRevokedReason.REVOKED_BY_USER, Instant.now());
        authEvents.save(UserAuthEvent.builder()
                .userId(userId)
                .eventType(AuthEventType.SESSION_REVOKED)
                .outcome(AuthOutcome.SUCCESS)
                .ipAddress(ipAddress)
                .build());
    }

    /** Called on every authenticated request. Empty means "not authenticated" -- an
     *  unknown token, an expired/revoked session, or an account that stopped being active
     *  after the session was issued. */
    @Transactional
    public Optional<User> validateSession(String rawSessionToken) {
        Optional<UserSession> maybeSession =
                sessions.findByTokenHash(SessionToken.hash(rawSessionToken));
        if (maybeSession.isEmpty()) {
            return Optional.empty();
        }
        UserSession session = maybeSession.get();
        Instant now = Instant.now();
        if (!session.isActive(now)) {
            return Optional.empty();
        }
        session.setLastSeenAt(now);
        sessions.save(session);

        return users.findById(session.getUserId())
                .filter(User::canLogIn);
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
