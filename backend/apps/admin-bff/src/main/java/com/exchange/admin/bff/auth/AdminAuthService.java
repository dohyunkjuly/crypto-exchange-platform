package com.exchange.admin.bff.auth;

import com.exchange.admin.audit.AdminAction;
import com.exchange.admin.audit.AdminAuditRepository;
import com.exchange.admin.bff.audit.AdminAuditService;
import com.exchange.admin.bff.common.errors.AdminErrorCode;
import com.exchange.admin.identity.AdminUser;
import com.exchange.admin.identity.AdminUserRepository;
import com.exchange.admin.session.AdminSession;
import com.exchange.admin.session.AdminSessionRepository;
import com.exchange.admin.session.AdminSessionState;
import com.exchange.common.crypto.AesGcmCipher;
import com.exchange.common.crypto.Totp;
import com.exchange.core.api.exception.ApiException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.OptionalLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Logging staff in, and everything a login needs: the password, the mandatory second
 * factor, the session it ends in, and the step-up a sensitive write asks for again.
 *
 * <p>A session is PENDING from the password until the TOTP code -- or, at first login, until
 * the authenticator is enrolled and its first code typed. Nothing but {@code /admin/auth/**}
 * accepts a PENDING session, so a stolen password alone opens nothing. There is no recovery
 * code: a locked-out admin is reset by a SUPERADMIN or the CLI. docs/reference/admin.md.
 */
@Service
public class AdminAuthService {

    static final Duration SESSION_IDLE = Duration.ofHours(8);
    static final Duration LOGIN_FAILURE_WINDOW = Duration.ofMinutes(15);
    static final int MAX_FAILURES_PER_EMAIL = 5;
    static final int MAX_FAILURES_PER_IP = 20;
    /** One step of drift either side, as customers get. */
    static final int DRIFT_STEPS = 1;

    private final AdminUserRepository admins;
    private final AdminSessionRepository sessions;
    private final AdminAuditRepository auditEntries;
    private final AdminAuditService audit;
    private final PasswordEncoder passwords;
    private final AesGcmCipher cipher;
    /** What the authenticator app shows above the code. The operator's name, never a brand
     *  from this repo: the product is white-label, and this string ends up on every staff phone. */
    private final String issuer;

    public AdminAuthService(AdminUserRepository admins, AdminSessionRepository sessions,
                            AdminAuditRepository auditEntries, AdminAuditService audit, PasswordEncoder passwords,
                            @Value("${MFA_ENCRYPTION_KEY}") String base64EncryptionKey,
                            @Value("${EXCHANGE_NAME}") String exchangeName) {
        this.admins = admins;
        this.sessions = sessions;
        this.auditEntries = auditEntries;
        this.audit = audit;
        this.passwords = passwords;
        this.cipher = new AesGcmCipher(Base64.getDecoder().decode(base64EncryptionKey));
        this.issuer = exchangeName.trim() + " Admin";
    }

    /** What the login answered, and what the browser must do next. */
    public record Login(AdminSessionToken token, AdminSession session, boolean totpEnrolled) {
    }

    /**
     * The password half. The session it mints is PENDING.
     *
     * @throws ApiException INVALID_CREDENTIALS, or TOO_MANY_ATTEMPTS after five failures for the
     *         address or twenty from the IP in fifteen minutes
     */
    @Transactional
    public Login login(String email, String rawPassword, String ipAddress, String userAgent) {
        Instant now = Instant.now();
        Instant windowStart = now.minus(LOGIN_FAILURE_WINDOW);
        if (auditEntries.countByActionAndTargetIdAndCreatedAtAfter(AdminAction.LOGIN_FAILED, email, windowStart)
                >= MAX_FAILURES_PER_EMAIL
                || auditEntries.countByActionAndIpAddressAndCreatedAtAfter(AdminAction.LOGIN_FAILED, ipAddress,
                        windowStart) >= MAX_FAILURES_PER_IP) {
            audit.recordFailedLogin(email, ipAddress, "RATE_LIMITED");
            throw new ApiException(AdminErrorCode.TOO_MANY_ATTEMPTS);
        }
        AdminUser admin = admins.findByEmail(email).orElse(null);
        if (admin == null || !admin.isEnabled() || !passwords.matches(rawPassword, admin.getPasswordHash())) {
            audit.recordFailedLogin(email, ipAddress, admin == null ? "NO_SUCH_ADMIN"
                    : !admin.isEnabled() ? "DISABLED" : "BAD_PASSWORD");
            throw new ApiException(AdminErrorCode.INVALID_CREDENTIALS);
        }
        AdminSessionToken token = AdminSessionToken.generate();
        AdminSession session = sessions.save(new AdminSession(admin.getId(), token.hash(), ipAddress, userAgent,
                now, now.plus(SESSION_IDLE)));
        return new Login(token, session, admin.isTotpConfirmed());
    }

    /**
     * First login: a fresh seed, stored encrypted and unconfirmed. The provisioning URI is
     * what the admin scans. Refused once an authenticator is confirmed -- a reset is an
     * admin operation, not something the owner of a password can do alone.
     */
    @Transactional
    public String startTotpEnrolment(Authenticated who) {
        AdminUser admin = who.admin();
        if (admin.isTotpConfirmed()) {
            throw new ApiException(AdminErrorCode.TOTP_ALREADY_ENROLLED);
        }
        String secret = Totp.generateSecret();
        admin.setTotpSecretEncrypted(cipher.encrypt(secret.getBytes(StandardCharsets.UTF_8)));
        admins.save(admin);
        return Totp.provisioningUri(issuer, admin.getEmail(), secret);
    }

    /** The code half. Confirms an enrolment in progress, and turns the session ACTIVE. */
    @Transactional
    public void completeLogin(Authenticated who, String code, String ipAddress) {
        AdminUser admin = who.admin();
        if (admin.getTotpSecretEncrypted() == null) {
            throw new ApiException(AdminErrorCode.TOTP_NOT_ENROLLED);
        }
        boolean enrolling = !admin.isTotpConfirmed();
        spendCode(admin, code);
        if (enrolling) {
            admin.setTotpConfirmedAt(Instant.now());
            audit.record(admin.getId(), AdminAction.TOTP_ENROLLED, "admin", admin.getId().toString(), null, null,
                    ipAddress);
        }
        admins.save(admin);
        who.session().setState(AdminSessionState.ACTIVE);
        sessions.save(who.session());
        audit.record(admin.getId(), AdminAction.LOGIN, "admin", admin.getId().toString(), null, null, ipAddress);
    }

    /** A fresh code for a write that cannot be undone. The code is spent, as at login. */
    @Transactional
    public void stepUp(AdminUser admin, String code) {
        if (code == null || !admin.isTotpConfirmed()) {
            throw new ApiException(AdminErrorCode.STEP_UP_REQUIRED);
        }
        spendCode(admin, code);
        admins.save(admin);
    }

    /** The row behind a cookie, if it is live. Touches last-seen. */
    @Transactional
    public Optional<Authenticated> validate(String rawToken) {
        Instant now = Instant.now();
        return sessions.findByTokenHash(AdminSessionToken.hash(rawToken))
                .filter(session -> session.isLive(now))
                .flatMap(session -> admins.findById(session.getAdminId())
                        .filter(AdminUser::isEnabled)
                        .map(admin -> {
                            session.setLastSeenAt(now);
                            session.setExpiresAt(now.plus(SESSION_IDLE));
                            return new Authenticated(admin, sessions.save(session));
                        }));
    }

    /** Tolerant of a cookie that is already dead: logging out must always succeed. */
    @Transactional
    public void logout(String rawToken, String ipAddress) {
        if (rawToken == null) {
            return;
        }
        sessions.findByTokenHash(AdminSessionToken.hash(rawToken)).ifPresent(session -> {
            if (session.getRevokedAt() == null) {
                session.setRevokedAt(Instant.now());
                sessions.save(session);
                audit.record(session.getAdminId(), AdminAction.LOGOUT, "admin", session.getAdminId().toString(),
                        null, null, ipAddress);
            }
        });
    }

    private void spendCode(AdminUser admin, String code) {
        String secret = new String(cipher.decrypt(admin.getTotpSecretEncrypted()), StandardCharsets.UTF_8);
        OptionalLong matched = Totp.verify(secret, code, Instant.now(), DRIFT_STEPS);
        Long spent = admin.getTotpLastUsedStep();
        if (matched.isEmpty() || (spent != null && matched.getAsLong() <= spent)) {
            throw new ApiException(AdminErrorCode.INVALID_TOTP_CODE);
        }
        admin.setTotpLastUsedStep(matched.getAsLong());
    }
}
