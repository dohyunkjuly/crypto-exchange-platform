package com.exchange.user.mfa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.exchange.common.crypto.AesGcmCipher;
import com.exchange.common.crypto.Sha256;
import com.exchange.common.crypto.Totp;
import com.exchange.user.audit.AuthEventType;
import com.exchange.user.audit.AuthOutcome;
import com.exchange.user.audit.UserAuthEvent;
import com.exchange.user.audit.UserAuthEventRepository;
import com.exchange.user.identity.User;
import com.exchange.user.identity.UserRepository;
import com.exchange.user.mfa.exception.InvalidMfaCodeException;
import com.exchange.user.mfa.exception.MfaAlreadyEnabledException;
import com.exchange.user.mfa.exception.MfaEnrolmentNotStartedException;
import com.exchange.user.mfa.exception.MfaNotEnabledException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * The rules that make a second factor worth having.
 *
 * <p>These are all "the write did or did not happen" assertions rather than "the call
 * returned". That is deliberate: three bugs in this codebase were writes Spring silently
 * discarded -- a transaction rolled back by the exception meant to report a failure, an
 * {@code AFTER_COMMIT} listener joining a transaction that had already committed -- and
 * every one of them looked fine from the response.
 *
 * <p>{@code disable} gets the most attention because it had no test at all, and it is the
 * one operation that takes the factor away. If its code check were broken, a stolen
 * session could strip 2FA and leave nothing behind it -- and the login challenge and the
 * API-key step-up both rest on that factor existing.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MfaServiceTest {

    private static final long USER_ID = 42L;
    private static final Instant NOW = Instant.parse("2026-09-07T12:00:00Z");

    /** Any 32 bytes; nothing here depends on the value, only on encrypt/decrypt agreeing. */
    private static final String KEY = Base64.getEncoder()
            .encodeToString(new byte[32]);

    @Mock
    private UserMfaMethodRepository methods;
    @Mock
    private UserRepository users;
    @Mock
    private UserMfaRecoveryCodeRepository recoveryCodes;
    @Mock
    private UserAuthEventRepository authEvents;

    private MfaService mfa;
    private User user;

    @BeforeEach
    void setUp() {
        mfa = new MfaService(methods, users, recoveryCodes, authEvents, KEY);
        user = new User("trader@example.com", "hash");
        when(users.findById(USER_ID)).thenReturn(Optional.of(user));
    }

    // ------------------------------------------------------------------ enrolment

    @Test
    @DisplayName("enrolment activates nothing until a code proves the authenticator works")
    void enrolmentIsInactiveUntilConfirmed() {
        when(methods.findFirstByUserIdAndMethodTypeAndVerifiedAtIsNullAndRevokedAtIsNull(
                USER_ID, MfaMethodType.TOTP)).thenReturn(Optional.empty());

        mfa.startTotpEnrolment(USER_ID, "trader@example.com", "Exchange");

        // Saved, but with no verifiedAt -- a mistyped or mis-scanned secret must not be
        // able to lock someone out of their own account.
        verify(methods).save(argThat(m -> m.getVerifiedAt() == null));
        verify(users, never()).save(any());
    }

    @Test
    @DisplayName("starting again supersedes the pending enrolment")
    void secondEnrolmentRevokesThePendingOne() {
        UserMfaMethod pending = totpMethod("JBSWY3DPEHPK3PXP");
        when(methods.findFirstByUserIdAndMethodTypeAndVerifiedAtIsNullAndRevokedAtIsNull(
                USER_ID, MfaMethodType.TOTP)).thenReturn(Optional.of(pending));

        mfa.startTotpEnrolment(USER_ID, "trader@example.com", "Exchange");

        // Two live secrets would mean a code from an abandoned attempt -- possibly on a
        // device the user no longer has -- still opens the account.
        assertThat(pending.getRevokedAt()).isNotNull();
    }

    @Test
    @DisplayName("cannot enrol over a working factor")
    void cannotEnrolWhenAlreadyEnabled() {
        when(methods.existsByUserIdAndVerifiedAtIsNotNullAndRevokedAtIsNull(USER_ID))
                .thenReturn(true);

        // Otherwise a stolen session could quietly swap the second factor for its own.
        assertThatThrownBy(() -> mfa.startTotpEnrolment(USER_ID, "a@b.c", "Exchange"))
                .isInstanceOf(MfaAlreadyEnabledException.class);
    }

    @Test
    @DisplayName("confirming with a wrong code activates nothing")
    void wrongConfirmationCodeActivatesNothing() {
        UserMfaMethod pending = totpMethod(Totp.generateSecret());
        when(methods.findFirstByUserIdAndMethodTypeAndVerifiedAtIsNullAndRevokedAtIsNull(
                USER_ID, MfaMethodType.TOTP)).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> mfa.confirmTotpEnrolment(USER_ID, "000000", "127.0.0.1"))
                .isInstanceOf(InvalidMfaCodeException.class);

        assertThat(pending.getVerifiedAt()).isNull();
        assertThat(user.isMfaEnabled()).isFalse();
        verify(recoveryCodes, never()).save(any());
    }

    @Test
    @DisplayName("confirming activates the factor, flips the read model, and issues codes")
    void confirmationActivatesAndIssuesRecoveryCodes() {
        String secret = Totp.generateSecret();
        UserMfaMethod pending = totpMethod(secret);
        when(methods.findFirstByUserIdAndMethodTypeAndVerifiedAtIsNullAndRevokedAtIsNull(
                USER_ID, MfaMethodType.TOTP)).thenReturn(Optional.of(pending));

        List<String> codes = mfa.confirmTotpEnrolment(USER_ID,
                Totp.codeAt(secret, Instant.now()), "127.0.0.1");

        assertThat(pending.getVerifiedAt()).isNotNull();
        // The gate reads user_users.mfa_enabled, not this table -- if the two disagree,
        // someone trades without a factor the system believes they have.
        assertThat(user.isMfaEnabled()).isTrue();
        assertThat(codes).hasSize(10).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("confirming without an enrolment in progress is refused")
    void confirmingWithoutEnrolment() {
        when(methods.findFirstByUserIdAndMethodTypeAndVerifiedAtIsNullAndRevokedAtIsNull(
                USER_ID, MfaMethodType.TOTP)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> mfa.confirmTotpEnrolment(USER_ID, "123456", "127.0.0.1"))
                .isInstanceOf(MfaEnrolmentNotStartedException.class);
    }

    // ------------------------------------------------------------------ challenge

    @Test
    @DisplayName("a TOTP code cannot be used twice")
    void totpCodeIsSingleUse() {
        String secret = Totp.generateSecret();
        UserMfaMethod active = activeTotp(secret);
        when(methods.findByUserIdAndVerifiedAtIsNotNullAndRevokedAtIsNull(USER_ID))
                .thenReturn(List.of(active));
        String code = Totp.codeAt(secret, Instant.now());

        mfa.challenge(USER_ID, code, "127.0.0.1");

        // Without the spent-step guard, a code read off someone's screen stays a working
        // second factor for the rest of its window plus the drift either side.
        assertThatThrownBy(() -> mfa.challenge(USER_ID, code, "127.0.0.1"))
                .isInstanceOf(InvalidMfaCodeException.class);
    }

    @Test
    @DisplayName("a recovery code works once and is then spent")
    void recoveryCodeIsSingleUse() {
        when(methods.findByUserIdAndVerifiedAtIsNotNullAndRevokedAtIsNull(USER_ID))
                .thenReturn(List.of(activeTotp(Totp.generateSecret())));
        UserMfaRecoveryCode recovery = recoveryCode("ABCD-EFGH-IJKL-MNOP", USER_ID);
        when(recoveryCodes.findByCodeHash(any())).thenReturn(Optional.of(recovery));

        mfa.challenge(USER_ID, "ABCD-EFGH-IJKL-MNOP", "127.0.0.1");
        assertThat(recovery.getUsedAt()).isNotNull();

        assertThatThrownBy(() -> mfa.challenge(USER_ID, "ABCD-EFGH-IJKL-MNOP", "127.0.0.1"))
                .isInstanceOf(InvalidMfaCodeException.class);
    }

    @Test
    @DisplayName("a recovery code belonging to someone else is refused")
    void recoveryCodeIsScopedToItsOwner() {
        when(methods.findByUserIdAndVerifiedAtIsNotNullAndRevokedAtIsNull(USER_ID))
                .thenReturn(List.of(activeTotp(Totp.generateSecret())));
        // Found by hash, but issued to another account.
        when(recoveryCodes.findByCodeHash(any()))
                .thenReturn(Optional.of(recoveryCode("ABCD-EFGH-IJKL-MNOP", 999L)));

        assertThatThrownBy(() -> mfa.challenge(USER_ID, "ABCD-EFGH-IJKL-MNOP", "127.0.0.1"))
                .isInstanceOf(InvalidMfaCodeException.class);
    }

    @Test
    @DisplayName("challenging an account with no factor is refused")
    void challengeWithoutAnyFactor() {
        when(methods.findByUserIdAndVerifiedAtIsNotNullAndRevokedAtIsNull(USER_ID))
                .thenReturn(List.of());

        assertThatThrownBy(() -> mfa.challenge(USER_ID, "123456", "127.0.0.1"))
                .isInstanceOf(MfaNotEnabledException.class);
    }

    // ------------------------------------------------------------------ disable

    /** The test this class was written for: disable had no coverage at all. */
    @Test
    @DisplayName("a wrong code leaves the factor and the recovery codes untouched")
    void disableRefusesAWrongCode() {
        UserMfaMethod active = activeTotp(Totp.generateSecret());
        when(methods.findByUserIdAndVerifiedAtIsNotNullAndRevokedAtIsNull(USER_ID))
                .thenReturn(List.of(active));
        when(recoveryCodes.findByCodeHash(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> mfa.disable(USER_ID, "000000", "127.0.0.1"))
                .isInstanceOf(InvalidMfaCodeException.class);

        // The whole point: a stolen session must not be able to strip 2FA.
        assertThat(active.getRevokedAt()).isNull();
        assertThat(user.isMfaEnabled()).isFalse(); // never turned on in this test
        verify(recoveryCodes, never()).deleteByUserId(anyLong());
    }

    @Test
    @DisplayName("a correct code revokes every factor, clears recovery codes, and flips the flag")
    void disableWithACorrectCode() {
        String secret = Totp.generateSecret();
        UserMfaMethod active = activeTotp(secret);
        user.setMfaEnabled(true);
        when(methods.findByUserIdAndVerifiedAtIsNotNullAndRevokedAtIsNull(USER_ID))
                .thenReturn(List.of(active));
        when(methods.findByUserId(USER_ID)).thenReturn(List.of(active));

        mfa.disable(USER_ID, Totp.codeAt(secret, Instant.now()), "127.0.0.1");

        assertThat(active.getRevokedAt()).isNotNull();
        assertThat(user.isMfaEnabled()).isFalse();
        // Unused recovery codes are credentials for an account that no longer has 2FA.
        verify(recoveryCodes).deleteByUserId(USER_ID);
    }

    // -------------------------------------------------- recovery-code regeneration

    @Test
    @DisplayName("a wrong code leaves the existing recovery codes in place")
    void regenerationRefusesAWrongCode() {
        when(methods.findByUserIdAndVerifiedAtIsNotNullAndRevokedAtIsNull(USER_ID))
                .thenReturn(List.of(activeTotp(Totp.generateSecret())));
        when(recoveryCodes.findByCodeHash(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> mfa.regenerateRecoveryCodes(USER_ID, "000000", "127.0.0.1"))
                .isInstanceOf(InvalidMfaCodeException.class);

        // Failing must not cost the user the codes they still have -- that would turn a
        // mistyped digit into the exact lockout this operation exists to prevent.
        verify(recoveryCodes, never()).deleteByUserId(anyLong());
        verify(recoveryCodes, never()).save(any());
    }

    @Test
    @DisplayName("a correct code retires the old set and issues ten new ones")
    void regenerationReplacesTheWholeSet() {
        String secret = Totp.generateSecret();
        user.setMfaEnabled(true);
        when(methods.findByUserIdAndVerifiedAtIsNotNullAndRevokedAtIsNull(USER_ID))
                .thenReturn(List.of(activeTotp(secret)));

        List<String> codes = mfa.regenerateRecoveryCodes(
                USER_ID, Totp.codeAt(secret, Instant.now()), "127.0.0.1");

        assertThat(codes).hasSize(10).doesNotHaveDuplicates();
        // Deleted before the new rows are written: leaving the old set alive would double
        // the number of credentials that open the account.
        verify(recoveryCodes).deleteByUserId(USER_ID);
        verify(recoveryCodes, times(10)).save(any());
        // The factor itself is untouched -- this rotates one credential, it does not
        // re-enrol, so mfa_enabled must not move in either direction.
        assertThat(user.isMfaEnabled()).isTrue();
        verify(authEvents).save(anEventWhere(
                e -> e.getEventType() == AuthEventType.MFA_RECOVERY_CODES_REISSUED
                        && e.getOutcome() == AuthOutcome.SUCCESS));
    }

    /** The scenario this endpoint was built for: authenticator gone, one code left. */
    @Test
    @DisplayName("the last recovery code can be spent to buy a fresh set")
    void aLastRecoveryCodeBuysTenMore() {
        when(methods.findByUserIdAndVerifiedAtIsNotNullAndRevokedAtIsNull(USER_ID))
                .thenReturn(List.of(activeTotp(Totp.generateSecret())));
        UserMfaRecoveryCode last = recoveryCode("ABCD-EFGH-IJKL-MNOP", USER_ID);
        when(recoveryCodes.findByCodeHash(any())).thenReturn(Optional.of(last));

        List<String> codes =
                mfa.regenerateRecoveryCodes(USER_ID, "ABCD-EFGH-IJKL-MNOP", "127.0.0.1");

        assertThat(codes).hasSize(10);
        // Spent on the way in, and then deleted with the rest of the old set anyway.
        assertThat(last.getUsedAt()).isNotNull();
        verify(recoveryCodes).deleteByUserId(USER_ID);
    }

    @Test
    @DisplayName("an account with no factor has no recovery codes to regenerate")
    void regenerationWithoutAnyFactor() {
        when(methods.findByUserIdAndVerifiedAtIsNotNullAndRevokedAtIsNull(USER_ID))
                .thenReturn(List.of());

        assertThatThrownBy(() -> mfa.regenerateRecoveryCodes(USER_ID, "123456", "127.0.0.1"))
                .isInstanceOf(MfaNotEnabledException.class);

        verify(recoveryCodes, never()).deleteByUserId(anyLong());
    }

    // ------------------------------------------------------------------ helpers

    private static UserMfaMethod argThat(java.util.function.Predicate<UserMfaMethod> p) {
        return org.mockito.ArgumentMatchers.argThat(p::test);
    }

    private UserMfaMethod totpMethod(String secret) {
        return UserMfaMethod.totp(USER_ID, "Authenticator app",
                new AesGcmCipher(Base64.getDecoder().decode(KEY))
                        .encrypt(secret.getBytes(StandardCharsets.UTF_8)),
                "v1");
    }

    private UserMfaMethod activeTotp(String secret) {
        UserMfaMethod method = totpMethod(secret);
        method.setVerifiedAt(NOW);
        return method;
    }

    private static UserAuthEvent anEventWhere(
            java.util.function.Predicate<UserAuthEvent> p) {
        return org.mockito.ArgumentMatchers.argThat(p::test);
    }

    private static UserMfaRecoveryCode recoveryCode(String code, long ownerId) {
        return new UserMfaRecoveryCode(ownerId,
                Sha256.of(code.replace("-", "").toUpperCase(java.util.Locale.ROOT)));
    }
}
