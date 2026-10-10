package com.exchange.bff.auth;

import com.exchange.bff.common.errors.BffHttpErrorCode;
import com.exchange.bff.common.mail.Mailer;
import com.exchange.common.crypto.Sha256;
import com.exchange.core.api.exception.ApiException;
import com.exchange.user.audit.AuthEventType;
import com.exchange.user.audit.AuthOutcome;
import com.exchange.user.audit.UserAuthEvent;
import com.exchange.user.audit.UserAuthEventRepository;
import com.exchange.user.email.UserEmailVerificationToken;
import com.exchange.user.email.UserEmailVerificationTokenRepository;
import com.exchange.user.identity.User;
import com.exchange.user.identity.UserRepository;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.security.MessageDigest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issuing and redeeming the code that proves someone can read their own inbox.
 *
 * <p>In web-bff rather than {@code libs/domain/user}, alongside {@code AuthService} and for
 * the same reason: spot-open-api has no signup flow to share it with, and it needs a
 * {@link Mailer}, which is this app's concern. The token table and the user row live in
 * the domain; the flow around them does not.
 */
@Service
public class EmailVerificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailVerificationService.class);

    /**
     * Fifteen minutes, not the twenty-four hours a link could afford.
     *
     * <p>Six digits is one of a million. The window is a direct multiplier on how many
     * guesses an attacker gets, so it is as short as someone switching to their mail app
     * can live with -- and a resend is one click away.
     */
    private static final Duration TTL = Duration.ofMinutes(15);

    /**
     * How long before another code can be asked for.
     *
     * <p>Without it the only bound is the global per-IP limiter, which allows a hundred
     * emails a minute to one inbox -- enough to bury someone's mail, burn provider quota,
     * and damage sending reputation, all from an endpoint that exists to help them.
     */
    private static final Duration RESEND_COOLDOWN = Duration.ofSeconds(60);

    /** Not zero-padded by accident: a code that can render as "042931" and be typed as
     *  "42931" is a support ticket. Always six characters. */
    private static final int CODE_BOUND = 1_000_000;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository users;
    private final UserEmailVerificationTokenRepository tokens;
    private final UserAuthEventRepository authEvents;
    private final Mailer mailer;

    public EmailVerificationService(UserRepository users,
                                    UserEmailVerificationTokenRepository tokens,
                                    UserAuthEventRepository authEvents,
                                    Mailer mailer) {
        this.users = users;
        this.tokens = tokens;
        this.authEvents = authEvents;
        this.mailer = mailer;
    }

    /**
     * Issues a fresh code and emails it. Any outstanding code stops working.
     *
     * <p>Does nothing for an already-verified account, so a stray call cannot un-verify
     * anyone or send a pointless email.
     */
    @Transactional
    public void send(User user, String ipAddress) {
        if (user.isEmailVerified()) {
            return;
        }
        tokens.findByUserIdAndUsedAtIsNull(user.getId())
                .filter(existing -> existing.getCreatedAt() != null)
                .filter(existing -> existing.getCreatedAt()
                        .isAfter(Instant.now().minus(RESEND_COOLDOWN)))
                .ifPresent(existing -> {
                    throw new ApiException(BffHttpErrorCode.CODE_REQUESTED_TOO_SOON);
                });

        // One live code at a time. Otherwise "resend" widens the window instead of
        // replacing it -- and with six digits, every additional live code is another
        // million-to-one draw an attacker gets for free.
        tokens.deleteByUserIdAndUsedAtIsNull(user.getId());

        String code = String.format("%06d", RANDOM.nextInt(CODE_BOUND));
        tokens.save(new UserEmailVerificationToken(user.getId(), Sha256.of(code),
                user.getEmail(), Instant.now().plus(TTL)));

        mailer.send(user.getEmail(), "Your verification code", body(code));
        authEvents.save(UserAuthEvent.builder()
                .userId(user.getId())
                .emailAttempted(user.getEmail())
                .eventType(AuthEventType.EMAIL_VERIFICATION_SENT)
                .outcome(AuthOutcome.SUCCESS)
                .ipAddress(ipAddress)
                .build());
    }

    /**
     * Redeems a code, for the user who asked for it.
     *
     * <p>Scoped to the authenticated user rather than looked up by code: six digits collide
     * across a million values, so a global lookup would let one account's code open
     * another's. It also binds the two halves together -- whoever redeems this has proved
     * they hold both the session and the inbox.
     *
     * <p>{@code noRollbackFor} is load-bearing. A wrong code throws, and a thrown
     * {@code RuntimeException} rolls the transaction back by default -- which would undo
     * the attempt counter along with it and leave the cap silently doing nothing, giving
     * an attacker a million free guesses at six digits. The rejection is an expected
     * outcome here, not a failure that should erase what it just recorded.
     *
     * @throws ApiException {@code INVALID_VERIFICATION_CODE} if it is wrong, expired,
     *         already used, exhausted by wrong guesses, or was issued for an address the
     *         account no longer has. One answer for all of them: saying which would tell
     *         an attacker whether they are close.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public void verify(User user, String code, String ipAddress) {
        UserEmailVerificationToken pending = tokens
                .findByUserIdAndUsedAtIsNull(user.getId())
                .orElseThrow(() -> new ApiException(
                        BffHttpErrorCode.INVALID_VERIFICATION_CODE));

        if (!pending.isRedeemableFor(user.getEmail(), Instant.now())) {
            throw new ApiException(BffHttpErrorCode.INVALID_VERIFICATION_CODE);
        }

        if (!MessageDigest.isEqual(pending.getCodeHash(), Sha256.of(code))) {
            // Counted, not just refused. This is what turns six digits from a million
            // free guesses into five.
            pending.recordFailedAttempt();
            tokens.save(pending);
            throw new ApiException(BffHttpErrorCode.INVALID_VERIFICATION_CODE);
        }

        pending.setUsedAt(Instant.now());
        tokens.save(pending);

        user.setEmailVerified(true);
        users.save(user);

        authEvents.save(UserAuthEvent.builder()
                .userId(user.getId())
                .emailAttempted(user.getEmail())
                .eventType(AuthEventType.EMAIL_VERIFIED)
                .outcome(AuthOutcome.SUCCESS)
                .ipAddress(ipAddress)
                .build());
    }

    /**
     * No link, by design -- see V1__user.sql.
     *
     * <p>The closing line is not filler. It is the only place a user is told the rule that
     * makes every phishing attempt against them fail, and it has to appear on the genuine
     * message for the absence of it to mean anything on a fake one.
     */
    private String body(String code) {
        return "Your verification code is:\n\n    " + code
                + "\n\nEnter it to confirm your email address. It expires in "
                + TTL.toMinutes() + " minutes.\n\n"
                + "We will never send you a link to click or ask you for this code. "
                + "If you did not create an account, ignore this message.";
    }
}
