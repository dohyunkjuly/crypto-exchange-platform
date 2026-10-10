package com.exchange.bff.auth;

import com.exchange.bff.common.errors.BffHttpErrorCode;
import com.exchange.core.api.exception.ApiException;
import com.exchange.user.audit.AuthEventType;
import com.exchange.user.audit.AuthOutcome;
import com.exchange.user.audit.UserAuthEvent;
import com.exchange.user.audit.UserAuthEventRepository;
import com.exchange.user.identity.User;
import com.exchange.user.identity.UserRepository;
import com.exchange.user.mfa.MfaService;
import com.exchange.user.session.SessionRevokedReason;
import com.exchange.user.session.UserSession;
import com.exchange.user.session.UserSessionRepository;
import java.time.Duration;
import java.time.Instant;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Changing the password from inside the account.
 *
 * <p>No emailed code, unlike a reset: the caller already holds a session. What it must prove is
 * that it is the account holder right now -- the second factor when one is on, the current
 * password when not -- so a session left open on a shared machine is not enough on its own.
 *
 * <p>Every other session is revoked and this one kept: whoever made the change is the one
 * person known to have the new password.
 */
@Service
public class PasswordChangeService {

    private static final Duration FAILURE_WINDOW = Duration.ofMinutes(15);
    /** Login's bound: a wrong current password is a password guess by another route. */
    private static final long MAX_FAILURES = 5;

    private final UserRepository users;
    private final UserSessionRepository sessions;
    private final UserAuthEventRepository authEvents;
    private final PasswordEncoder passwordEncoder;
    private final MfaService mfa;

    public PasswordChangeService(UserRepository users, UserSessionRepository sessions,
                                 UserAuthEventRepository authEvents,
                                 PasswordEncoder passwordEncoder, MfaService mfa) {
        this.users = users;
        this.sessions = sessions;
        this.authEvents = authEvents;
        this.passwordEncoder = passwordEncoder;
        this.mfa = mfa;
    }

    /**
     * @throws com.exchange.user.mfa.exception.InvalidMfaCodeException 2FA is on and the code is
     *         missing or wrong -- the current password does not stand in for it
     * @throws ApiException {@code TOO_MANY_ATTEMPTS}, {@code NO_PASSWORD_SET} (2FA off and
     *         nothing to check: a Google-only account), {@code INVALID_CURRENT_PASSWORD}
     */
    // noRollbackFor keeps the failure event when this throws, or the cap would count nothing.
    @Transactional(noRollbackFor = ApiException.class)
    public void change(User user, String rawSessionToken, String newPassword,
                       String currentPassword, String mfaCode, String ipAddress) {
        Instant now = Instant.now();
        if (user.isMfaEnabled()) {
            mfa.challenge(user.getId(), mfaCode, ipAddress);
        } else {
            long failures = authEvents.countByEmailAttemptedAndEventTypeAndOutcomeAndCreatedAtAfter(
                    user.getEmail(), AuthEventType.PASSWORD_CHANGED, AuthOutcome.FAILURE,
                    now.minus(FAILURE_WINDOW));
            if (failures >= MAX_FAILURES) {
                record(user, AuthOutcome.FAILURE, ipAddress, "RATE_LIMITED");
                throw new ApiException(BffHttpErrorCode.TOO_MANY_ATTEMPTS);
            }
            if (user.getPasswordHash() == null) {
                throw new ApiException(BffHttpErrorCode.NO_PASSWORD_SET);
            }
            if (currentPassword == null
                    || !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
                record(user, AuthOutcome.FAILURE, ipAddress, "BAD_PASSWORD");
                throw new ApiException(BffHttpErrorCode.INVALID_CURRENT_PASSWORD);
            }
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        users.save(user);

        Long keep = sessions.findByTokenHash(SessionToken.hash(rawSessionToken))
                .map(UserSession::getId).orElse(null);
        if (keep == null) {
            sessions.revokeAllForUser(user.getId(), SessionRevokedReason.PASSWORD_CHANGED, now);
        } else {
            sessions.revokeAllForUserExcept(user.getId(), keep, SessionRevokedReason.PASSWORD_CHANGED, now);
        }
        record(user, AuthOutcome.SUCCESS, ipAddress, null);
    }

    private void record(User user, AuthOutcome outcome, String ipAddress, String failureReason) {
        authEvents.save(UserAuthEvent.builder()
                .userId(user.getId())
                .emailAttempted(user.getEmail())
                .eventType(AuthEventType.PASSWORD_CHANGED)
                .outcome(outcome)
                .failureReason(failureReason)
                .ipAddress(ipAddress)
                .build());
    }
}
