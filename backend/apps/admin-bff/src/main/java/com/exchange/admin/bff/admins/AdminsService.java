package com.exchange.admin.bff.admins;

import com.exchange.admin.audit.AdminAction;
import com.exchange.admin.bff.audit.AdminAuditService;
import com.exchange.admin.bff.common.errors.AdminErrorCode;
import com.exchange.admin.identity.AdminRole;
import com.exchange.admin.identity.AdminUser;
import com.exchange.admin.identity.AdminUserRepository;
import com.exchange.admin.session.AdminSession;
import com.exchange.admin.session.AdminSessionRepository;
import com.exchange.core.api.exception.ApiException;
import java.time.Instant;
import java.util.List;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminsService {

    private final AdminUserRepository admins;
    private final AdminSessionRepository sessions;
    private final AdminAuditService audit;
    private final PasswordEncoder passwords;

    public AdminsService(AdminUserRepository admins, AdminSessionRepository sessions, AdminAuditService audit,
                         PasswordEncoder passwords) {
        this.admins = admins;
        this.sessions = sessions;
        this.audit = audit;
        this.passwords = passwords;
    }

    public List<AdminUser> list() {
        return admins.findAll();
    }

    /**
     * The same row the CLI makes, from a SUPERADMIN's session instead of a terminal. The new admin
     * enrols an authenticator at first login, as everyone does; the password is theirs to change.
     */
    @Transactional
    public AdminUser create(Long by, String email, String password, AdminRole role, String ip) {
        if (password == null || password.length() < 12) {
            throw new ApiException(AdminErrorCode.WEAK_PASSWORD);
        }
        if (admins.findByEmail(email).isPresent()) {
            throw new ApiException(AdminErrorCode.EMAIL_ALREADY_REGISTERED);
        }
        AdminUser made = admins.save(new AdminUser(email, passwords.encode(password), role));
        audit.record(by, AdminAction.ADMIN_CREATED, "admin", made.getId().toString(), null,
                "{\"email\":\"" + email + "\",\"role\":\"" + role + "\"}", ip);
        return made;
    }

    /**
     * A locked-out admin: the seed goes, every session ends, and the next login enrols afresh.
     * There is no recovery code by design (docs/reference/admin.md); this is the recovery.
     */
    @Transactional
    public AdminUser resetTotp(Long by, Long id, String ip) {
        if (id.equals(by)) {
            throw new ApiException(AdminErrorCode.CANNOT_ACT_ON_SELF);
        }
        AdminUser admin = admins.findById(id).orElseThrow(() -> new ApiException(AdminErrorCode.ADMIN_NOT_FOUND));
        admin.setTotpSecretEncrypted(null);
        admin.setTotpConfirmedAt(null);
        admin.setTotpLastUsedStep(null);
        endSessions(id);
        audit.record(by, AdminAction.ADMIN_TOTP_RESET, "admin", id.toString(), null, null, ip);
        return admins.save(admin);
    }

    /** Re-roling yourself is allowed: with a fresh code, dropping your own privileges is a
     *  deliberate act, and another SUPERADMIN can restore them. The lockouts are the two below. */
    @Transactional
    public AdminUser changeRole(Long by, Long id, AdminRole role, String ip) {
        AdminUser admin = admins.findById(id).orElseThrow(() -> new ApiException(AdminErrorCode.ADMIN_NOT_FOUND));
        AdminRole before = admin.getRole();
        admin.setRole(role);
        audit.record(by, AdminAction.ADMIN_ROLE_CHANGED, "admin", id.toString(),
                "{\"role\":\"" + before + "\"}", "{\"role\":\"" + role + "\"}", ip);
        return admins.save(admin);
    }

    /** Disabling also ends every live session: the next request is a 401, not the end of the day. */
    @Transactional
    public AdminUser disable(Long by, Long id, String ip) {
        if (id.equals(by)) {
            throw new ApiException(AdminErrorCode.CANNOT_ACT_ON_SELF);
        }
        AdminUser admin = admins.findById(id).orElseThrow(() -> new ApiException(AdminErrorCode.ADMIN_NOT_FOUND));
        admin.setDisabledAt(Instant.now());
        endSessions(id);
        audit.record(by, AdminAction.ADMIN_DISABLED, "admin", id.toString(), null, null, ip);
        return admins.save(admin);
    }

    private void endSessions(Long id) {
        Instant now = Instant.now();
        for (AdminSession session : sessions.findByAdminIdAndRevokedAtIsNullAndExpiresAtAfter(id, now)) {
            session.setRevokedAt(now);
            sessions.save(session);
        }
    }
}
