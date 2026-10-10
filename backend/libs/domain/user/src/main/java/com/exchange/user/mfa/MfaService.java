package com.exchange.user.mfa;

import com.exchange.common.crypto.AesGcmCipher;
import com.exchange.common.crypto.Base32;
import com.exchange.common.crypto.Sha256;
import com.exchange.common.crypto.Totp;
import com.exchange.user.audit.AuthEventType;
import com.exchange.user.audit.AuthOutcome;
import com.exchange.user.audit.UserAuthEvent;
import com.exchange.user.audit.UserAuthEventRepository;
import com.exchange.user.identity.User;
import com.exchange.user.identity.UserRepository;
import com.exchange.user.mfa.dto.TotpEnrolment;
import com.exchange.user.mfa.exception.InvalidMfaCodeException;
import com.exchange.user.mfa.exception.MfaAlreadyEnabledException;
import com.exchange.user.mfa.exception.MfaEnrolmentNotStartedException;
import com.exchange.user.mfa.exception.MfaNotEnabledException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Enrolling and checking a second factor.
 *
 * <p>Lives in {@code libs/domain/user} rather than in web-bff, unlike {@code AuthService}:
 * {@link com.exchange.user.apikey.ApiKeyService} is itself in this module and has to
 * refuse to mint a key for an account with no second factor, so the check cannot sit in an
 * app. Throws plain, code-less exceptions for the same reason -- each app maps them to its
 * own error codes (AGENTS.md).
 *
 * <p>TOTP only for now. The table also models WEBAUTHN, SMS and EMAIL, and everything here
 * is written against "does this user have any usable factor" rather than "does this user
 * have TOTP", so adding a second kind later does not change the gates.
 */
@Service
public class MfaService {

    /** Ten is what GitHub and Google both settle on: enough to survive years of
     *  occasional use, few enough to print on one line of a page someone actually keeps. */
    private static final int RECOVERY_CODE_COUNT = 10;

    /** 80 bits per code. These are checked online behind login rate limiting, never
     *  offline, so this is far past what guessing could reach. */
    private static final int RECOVERY_CODE_BYTES = 10;

    /** One step either side, ~30s of clock skew. Every extra step widens the window a
     *  stolen code stays live in. */
    private static final int DRIFT_STEPS = 1;

    /** Bumped if MFA_ENCRYPTION_KEY is ever rotated -- see secret_key_id in V1__user.sql. */
    private static final String KEY_ID = "v1";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserMfaMethodRepository methods;
    private final UserRepository users;
    private final UserMfaRecoveryCodeRepository recoveryCodes;
    private final UserAuthEventRepository authEvents;
    private final AesGcmCipher cipher;

    /**
     * A key of its own rather than sharing {@code API_KEY_ENCRYPTION_KEY}. Two different
     * credentials under one key means rotating either forces re-encrypting both, and a
     * leak of one key exposes both. The Spring wiring is inline here rather than in a
     * {@code @Configuration} class, matching {@code ApiKeyService} (docs/STYLE.md).
     */
    public MfaService(UserMfaMethodRepository methods,
                      UserRepository users,
                      UserMfaRecoveryCodeRepository recoveryCodes,
                      UserAuthEventRepository authEvents,
                      @Value("${MFA_ENCRYPTION_KEY}") String base64EncryptionKey) {
        this.methods = methods;
        this.users = users;
        this.recoveryCodes = recoveryCodes;
        this.authEvents = authEvents;
        this.cipher = new AesGcmCipher(Base64.getDecoder().decode(base64EncryptionKey));
    }

    /**
     * Does this account have a factor it could actually authenticate with right now?
     *
     * <p>Reads the methods table rather than {@code user_users.mfa_enabled}, on purpose:
     * this is the authority the read model is derived from, and it is not on any hot path.
     * The gate reads the denormalised flag; this answers the same question from the record.
     */
    @Transactional(readOnly = true)
    public boolean isEnabled(Long userId) {
        return methods.existsByUserIdAndVerifiedAtIsNotNullAndRevokedAtIsNull(userId);
    }

    /**
     * Step one: mint a secret and hand back something an authenticator can scan.
     *
     * <p>The row is saved unverified, so it counts for nothing until {@link
     * #confirmTotpEnrolment} proves the user can produce a code with it. Enrolling without
     * that proof is how a mistyped setup locks someone out of their own account.
     *
     * <p>Starting again discards any enrolment already in progress rather than keeping
     * both: two pending secrets would mean a code from either opens the account, including
     * one from an abandoned attempt on a device the user no longer has.
     *
     * @throws MfaAlreadyEnabledException if a verified factor already exists
     */
    @Transactional
    public TotpEnrolment startTotpEnrolment(Long userId, String accountName, String issuer) {
        return startTotpEnrolment(userId, accountName, issuer, Totp.generateSecret());
    }

    /**
     * The same, with the secret supplied. Only for seeding known dev accounts, whose secret must
     * survive a database reset so a developer's authenticator app keeps working; nothing that
     * serves a request may call this, since a caller-chosen secret is a caller-known secret.
     */
    public TotpEnrolment startTotpEnrolment(Long userId, String accountName, String issuer, String secret) {
        if (isEnabled(userId)) {
            throw new MfaAlreadyEnabledException();
        }
        methods.findFirstByUserIdAndMethodTypeAndVerifiedAtIsNullAndRevokedAtIsNull(
                        userId, MfaMethodType.TOTP)
                .ifPresent(pending -> {
                    pending.setRevokedAt(Instant.now());
                    methods.save(pending);
                });

        methods.save(UserMfaMethod.totp(userId, "Authenticator app",
                cipher.encrypt(secret.getBytes(StandardCharsets.UTF_8)), KEY_ID));

        return new TotpEnrolment(secret, Totp.provisioningUri(issuer, accountName, secret));
    }

    /**
     * Step two: prove the authenticator works, and only then activate it.
     *
     * @return the recovery codes, in plaintext, the only time they will ever exist. They
     *         are stored hashed, so a user who loses these and their authenticator is out
     *         of options -- which is exactly why the caller must show them.
     * @throws MfaEnrolmentNotStartedException nothing pending to confirm
     * @throws InvalidMfaCodeException         the code does not match the pending secret
     */
    @Transactional
    public List<String> confirmTotpEnrolment(Long userId, String code, String ipAddress) {
        UserMfaMethod pending = methods
                .findFirstByUserIdAndMethodTypeAndVerifiedAtIsNullAndRevokedAtIsNull(
                        userId, MfaMethodType.TOTP)
                .orElseThrow(MfaEnrolmentNotStartedException::new);

        Instant now = Instant.now();
        OptionalLong matched = Totp.verify(decryptSecret(pending), code, now, DRIFT_STEPS);
        if (matched.isEmpty()) {
            record(userId, AuthEventType.MFA_CHALLENGE_FAILED, AuthOutcome.FAILURE, ipAddress);
            throw new InvalidMfaCodeException();
        }

        pending.setVerifiedAt(now);
        pending.setLastUsedAt(now);
        pending.setLastUsedStep(matched.getAsLong());
        methods.save(pending);

        // Same transaction as the row above: user_users.mfa_enabled is the read model the
        // trading gate consults, and the two must never disagree. See V1__user.sql.
        setMfaEnabled(userId, true);

        List<String> codes = issueRecoveryCodes(userId);
        record(userId, AuthEventType.MFA_ENABLED, AuthOutcome.SUCCESS, ipAddress);
        return codes;
    }

    /**
     * The challenge itself: login, and step-up before anything sensitive.
     *
     * <p>Accepts either a six-digit TOTP code or a recovery code, told apart by shape. A
     * recovery code is spent on use and does not come back.
     *
     * @throws MfaNotEnabledException  the account has no active factor to challenge
     * @throws InvalidMfaCodeException wrong, expired, or already spent
     */
    @Transactional
    public void challenge(Long userId, String code, String ipAddress) {
        List<UserMfaMethod> active =
                methods.findByUserIdAndVerifiedAtIsNotNullAndRevokedAtIsNull(userId);
        if (active.isEmpty()) {
            throw new MfaNotEnabledException();
        }
        if (code == null || code.isBlank()) {
            record(userId, AuthEventType.MFA_CHALLENGE_FAILED, AuthOutcome.FAILURE, ipAddress);
            throw new InvalidMfaCodeException();
        }

        boolean passed = looksLikeTotp(code)
                ? consumeTotp(active, code)
                : consumeRecoveryCode(userId, code);

        record(userId,
                passed ? AuthEventType.MFA_CHALLENGE_SUCCEEDED : AuthEventType.MFA_CHALLENGE_FAILED,
                passed ? AuthOutcome.SUCCESS : AuthOutcome.FAILURE,
                ipAddress);
        if (!passed) {
            throw new InvalidMfaCodeException();
        }
    }

    /**
     * Turning it off requires passing it -- otherwise a stolen session could strip the
     * factor and leave nothing behind it.
     *
     * @throws MfaNotEnabledException  nothing enabled to disable
     * @throws InvalidMfaCodeException the code did not check out
     */
    @Transactional
    public void disable(Long userId, String code, String ipAddress) {
        challenge(userId, code, ipAddress);

        Instant now = Instant.now();
        for (UserMfaMethod method : methods.findByUserId(userId)) {
            if (method.getRevokedAt() == null) {
                method.setRevokedAt(now);
                methods.save(method);
            }
        }
        // Unused recovery codes are credentials for an account that no longer has 2FA.
        recoveryCodes.deleteByUserId(userId);
        setMfaEnabled(userId, false);
        record(userId, AuthEventType.MFA_DISABLED, AuthOutcome.SUCCESS, ipAddress);
    }

    /**
     * Issues a fresh set of recovery codes, retiring the old set.
     *
     * <p>Without this, recovery codes are a supply that only ever runs down: they are
     * issued exactly once, at enrolment, and spent one at a time. A user who burns the
     * last one and then loses their authenticator has no way back in -- {@link #disable}
     * deliberately requires passing the factor they no longer have, so the only remedy is
     * a hand-written DELETE by an operator.
     *
     * <p>Requires passing the factor, like {@link #disable} and for the same reason, and
     * accepts a recovery code for it as {@link #challenge} does. That last part looks
     * wrong at first -- a leaked sheet of codes could rotate the set out from under its
     * owner -- but a leaked sheet can already strip 2FA outright through {@code disable},
     * so refusing it here would buy nothing while closing the one path that matters: down
     * to a last code with the authenticator gone, spending it for ten more is the whole
     * point of the operation.
     *
     * @return the new codes, in plaintext, the only time they will ever exist
     * @throws MfaNotEnabledException  no active factor -- there is nothing to recover into
     * @throws InvalidMfaCodeException the code did not check out; the old set still stands
     */
    @Transactional
    public List<String> regenerateRecoveryCodes(Long userId, String code, String ipAddress) {
        challenge(userId, code, ipAddress);

        // issueRecoveryCodes deletes the previous set first, so a code the user already
        // read off the old sheet -- including the one they may have just spent getting
        // here -- stops working the moment this returns.
        List<String> codes = issueRecoveryCodes(userId);
        record(userId, AuthEventType.MFA_RECOVERY_CODES_REISSUED, AuthOutcome.SUCCESS,
                ipAddress);
        return codes;
    }

    /** How many one-time codes are left, so a client can warn before they run out. */
    @Transactional(readOnly = true)
    public long remainingRecoveryCodes(Long userId) {
        return recoveryCodes.countByUserIdAndUsedAtIsNull(userId);
    }

    /** When the active factor was confirmed -- a settings screen's "Added" date. Empty when none is. */
    @Transactional(readOnly = true)
    public Optional<Instant> enabledAt(Long userId) {
        return methods.findByUserIdAndVerifiedAtIsNotNullAndRevokedAtIsNull(userId).stream()
                .map(UserMfaMethod::getVerifiedAt)
                .min(Comparator.naturalOrder());
    }

    // ------------------------------------------------------------------ internals

    /**
     * Checks the code against every active TOTP factor, and burns the step it matched.
     *
     * <p>The {@code step <= lastUsedStep} rejection is the replay guard: a code is valid
     * for its own 30-second step plus the drift either side, so without this one read off
     * a screen keeps working for up to 90 seconds after its owner used it.
     */
    private boolean consumeTotp(List<UserMfaMethod> active, String code) {
        Instant now = Instant.now();
        for (UserMfaMethod method : active) {
            if (method.getMethodType() != MfaMethodType.TOTP) {
                continue;
            }
            OptionalLong matched = Totp.verify(decryptSecret(method), code, now, DRIFT_STEPS);
            if (matched.isEmpty()) {
                continue;
            }
            Long spent = method.getLastUsedStep();
            if (spent != null && matched.getAsLong() <= spent) {
                return false;
            }
            method.setLastUsedAt(now);
            method.setLastUsedStep(matched.getAsLong());
            methods.save(method);
            return true;
        }
        return false;
    }

    private boolean consumeRecoveryCode(Long userId, String code) {
        Optional<UserMfaRecoveryCode> found =
                recoveryCodes.findByCodeHash(Sha256.of(normalise(code)));
        // The hash is globally unique, but the owner is still checked: a code must not be
        // spendable by whoever happens to present it.
        if (found.isEmpty() || !found.get().getUserId().equals(userId) || found.get().isSpent()) {
            return false;
        }
        UserMfaRecoveryCode recovery = found.get();
        recovery.setUsedAt(Instant.now());
        recoveryCodes.save(recovery);
        return true;
    }

    private List<String> issueRecoveryCodes(Long userId) {
        recoveryCodes.deleteByUserId(userId);
        List<String> issued = new ArrayList<>(RECOVERY_CODE_COUNT);
        for (int i = 0; i < RECOVERY_CODE_COUNT; i++) {
            byte[] entropy = new byte[RECOVERY_CODE_BYTES];
            RANDOM.nextBytes(entropy);
            String code = format(Base32.encode(entropy));
            issued.add(code);
            recoveryCodes.save(new UserMfaRecoveryCode(userId, Sha256.of(normalise(code))));
        }
        return issued;
    }

    private void setMfaEnabled(Long userId, boolean enabled) {
        User user = users.findById(userId).orElseThrow(
                () -> new IllegalArgumentException("no such user: " + userId));
        user.setMfaEnabled(enabled);
        users.save(user);
    }

    private String decryptSecret(UserMfaMethod method) {
        return new String(cipher.decrypt(method.getSecretEncrypted()), StandardCharsets.UTF_8);
    }

    private static boolean looksLikeTotp(String code) {
        return code.length() == 6 && code.chars().allMatch(Character::isDigit);
    }

    /** Hyphens and case are presentation: someone reading a code off paper should not be
     *  punished for typing it the way it looks. */
    private static String normalise(String code) {
        return code.replace("-", "").replace(" ", "").toUpperCase(Locale.ROOT);
    }

    /** XXXX-XXXX-XXXX-XXXX -- grouped because these get copied by hand. */
    private static String format(String raw) {
        return raw.replaceAll("(.{4})(?=.)", "$1-");
    }

    private void record(Long userId, AuthEventType type, AuthOutcome outcome, String ipAddress) {
        authEvents.save(UserAuthEvent.builder()
                .userId(userId)
                .eventType(type)
                .outcome(outcome)
                .ipAddress(ipAddress)
                .build());
    }
}
