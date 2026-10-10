package com.exchange.admin.bff.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.exchange.admin.audit.AdminAction;
import com.exchange.admin.audit.AdminAuditRepository;
import com.exchange.admin.bff.audit.AdminAuditService;
import com.exchange.admin.bff.common.errors.AdminErrorCode;
import com.exchange.admin.identity.AdminRole;
import com.exchange.admin.identity.AdminUser;
import com.exchange.admin.identity.AdminUserRepository;
import com.exchange.admin.session.AdminSession;
import com.exchange.admin.session.AdminSessionRepository;
import com.exchange.admin.session.AdminSessionState;
import com.exchange.common.crypto.Totp;
import com.exchange.core.api.exception.ApiException;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminAuthServiceTest {

    private static final String KEY = "Hx4dHBsaGRgXFhUUExIREA8ODQwLCgkIBwYFBAMCAQA=";
    private static final String IP = "10.0.0.1";

    @Mock
    private AdminUserRepository admins;
    @Mock
    private AdminSessionRepository sessions;
    @Mock
    private AdminAuditRepository auditEntries;
    @Mock
    private AdminAuditService audit;
    @Mock
    private PasswordEncoder passwords;

    private AdminAuthService auth;
    private AdminUser admin;

    @BeforeEach
    void setUp() {
        auth = new AdminAuthService(admins, sessions, auditEntries, audit, passwords, KEY, "Example Exchange");
        admin = new AdminUser("ops@x.dev", "hash", AdminRole.OPERATOR);
        ReflectionTestUtils.setField(admin, "id", 1L);
        when(admins.findByEmail("ops@x.dev")).thenReturn(Optional.of(admin));
        when(passwords.matches("right", "hash")).thenReturn(true);
        when(sessions.save(any())).thenAnswer(call -> call.getArgument(0));
        when(admins.save(any())).thenAnswer(call -> call.getArgument(0));
    }

    private static ApiException code(ThrowingCall call) {
        try {
            call.run();
        } catch (ApiException e) {
            return e;
        }
        throw new AssertionError("nothing was thrown");
    }

    private interface ThrowingCall {
        void run();
    }

    @Test
    @DisplayName("a right password mints a PENDING session: a stolen password alone opens nothing")
    void loginIsOnlyHalfOfIt() {
        AdminAuthService.Login login = auth.login("ops@x.dev", "right", IP, "ua");

        assertThat(login.session().getState()).isEqualTo(AdminSessionState.PENDING);
        assertThat(login.totpEnrolled()).as("no authenticator yet: the browser must enrol one").isFalse();
        assertThat(login.token().raw()).isNotBlank();
    }

    @Test
    @DisplayName("a wrong password, an unknown address and a disabled admin all read the same, and are recorded")
    void everyFailureLooksAlike() {
        assertThat(code(() -> auth.login("ops@x.dev", "wrong", IP, "ua")).getErrorCode())
                .isEqualTo(AdminErrorCode.INVALID_CREDENTIALS);
        assertThat(code(() -> auth.login("nobody@x.dev", "right", IP, "ua")).getErrorCode())
                .isEqualTo(AdminErrorCode.INVALID_CREDENTIALS);
        admin.setDisabledAt(Instant.now());
        assertThat(code(() -> auth.login("ops@x.dev", "right", IP, "ua")).getErrorCode())
                .isEqualTo(AdminErrorCode.INVALID_CREDENTIALS);

        verify(audit).recordFailedLogin("ops@x.dev", IP, "BAD_PASSWORD");
        verify(audit).recordFailedLogin("nobody@x.dev", IP, "NO_SUCH_ADMIN");
        verify(audit).recordFailedLogin("ops@x.dev", IP, "DISABLED");
        verify(sessions, never()).save(any());
    }

    @Test
    @DisplayName("five failures for an address in fifteen minutes lock it, right password or not")
    void lockout() {
        when(auditEntries.countByActionAndTargetIdAndCreatedAtAfter(eq(AdminAction.LOGIN_FAILED), eq("ops@x.dev"),
                any())).thenReturn(5L);

        assertThat(code(() -> auth.login("ops@x.dev", "right", IP, "ua")).getErrorCode())
                .isEqualTo(AdminErrorCode.TOO_MANY_ATTEMPTS);
    }

    @Test
    @DisplayName("enrolment then the first right code confirms the authenticator and activates the session")
    void firstLogin() {
        AdminAuthService.Login login = auth.login("ops@x.dev", "right", IP, "ua");
        Authenticated who = new Authenticated(admin, login.session());

        String uri = auth.startTotpEnrolment(who);
        String secret = uri.replaceAll(".*secret=([A-Z2-7]+).*", "$1");
        assertThat(admin.isTotpConfirmed()).as("scanned is not confirmed").isFalse();

        auth.completeLogin(who, Totp.codeAt(secret, Instant.now()), IP);

        assertThat(admin.isTotpConfirmed()).isTrue();
        assertThat(login.session().getState()).isEqualTo(AdminSessionState.ACTIVE);
        verify(audit).record(eq(admin.getId()), eq(AdminAction.TOTP_ENROLLED), anyString(), any(), any(), any(),
                eq(IP));
        verify(audit).record(eq(admin.getId()), eq(AdminAction.LOGIN), anyString(), any(), any(), any(), eq(IP));
    }

    @Test
    @DisplayName("a confirmed authenticator cannot be enrolled again by whoever holds the password")
    void noReEnrolment() {
        enrolled();
        Authenticated who = new Authenticated(admin, auth.login("ops@x.dev", "right", IP, "ua").session());

        assertThat(code(() -> auth.startTotpEnrolment(who)).getErrorCode())
                .isEqualTo(AdminErrorCode.TOTP_ALREADY_ENROLLED);
    }

    @Test
    @DisplayName("a wrong code leaves the session PENDING, and a right code is spent: the same one does not work twice")
    void codesAreCheckedAndSpent() {
        String secret = enrolled();
        Authenticated who = new Authenticated(admin, auth.login("ops@x.dev", "right", IP, "ua").session());
        String code = Totp.codeAt(secret, Instant.now());

        assertThat(code(() -> auth.completeLogin(who, "000000", IP)).getErrorCode())
                .isEqualTo(AdminErrorCode.INVALID_TOTP_CODE);
        assertThat(who.session().getState()).isEqualTo(AdminSessionState.PENDING);

        auth.completeLogin(who, code, IP);
        assertThat(who.session().getState()).isEqualTo(AdminSessionState.ACTIVE);

        assertThat(code(() -> auth.stepUp(admin, code)).getErrorCode())
                .as("the login spent it; a step-up needs a fresh one")
                .isEqualTo(AdminErrorCode.INVALID_TOTP_CODE);
    }

    @Test
    @DisplayName("a step-up without a code, or before an authenticator exists, is refused as such")
    void stepUpNeedsACode() {
        assertThat(code(() -> auth.stepUp(admin, null)).getErrorCode()).isEqualTo(AdminErrorCode.STEP_UP_REQUIRED);
        assertThat(code(() -> auth.stepUp(admin, "123456")).getErrorCode())
                .isEqualTo(AdminErrorCode.STEP_UP_REQUIRED);
    }

    @Test
    @DisplayName("a live session validates and slides its expiry; a revoked, expired or disabled one does not")
    void validate() {
        AdminSession session = new AdminSession(1L, AdminSessionToken.hash("t"), IP, "ua", Instant.now(),
                Instant.now().plusSeconds(60));
        when(sessions.findByTokenHash(AdminSessionToken.hash("t"))).thenReturn(Optional.of(session));
        when(admins.findById(1L)).thenReturn(Optional.of(admin));

        assertThat(auth.validate("t")).isPresent();
        assertThat(session.getExpiresAt()).isAfter(Instant.now().plusSeconds(3600));

        session.setRevokedAt(Instant.now());
        assertThat(auth.validate("t")).isEmpty();
        session.setRevokedAt(null);
        admin.setDisabledAt(Instant.now());
        assertThat(auth.validate("t")).isEmpty();
        assertThat(auth.validate("unknown")).isEmpty();
    }

    /** An admin whose authenticator is already confirmed. Returns the seed. */
    private String enrolled() {
        String secret = Totp.generateSecret();
        Authenticated who = new Authenticated(admin, mock(AdminSession.class));
        String uri = auth.startTotpEnrolment(who);
        String generated = uri.replaceAll(".*secret=([A-Z2-7]+).*", "$1");
        admin.setTotpConfirmedAt(Instant.now());
        return generated.isEmpty() ? secret : generated;
    }
}
