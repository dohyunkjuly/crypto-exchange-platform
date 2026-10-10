package com.exchange.admin.bff.audit;

import com.exchange.admin.audit.AdminAction;
import com.exchange.admin.audit.AdminAuditEntry;
import com.exchange.admin.audit.AdminAuditRepository;
import com.exchange.admin.bff.audit.dto.AuditViews.Entry;
import com.exchange.admin.bff.audit.dto.AuditViews.Page_;
import com.exchange.admin.identity.AdminUser;
import com.exchange.admin.identity.AdminUserRepository;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The audit log: written by every admin write, and read back (docs/reference/admin.md).
 *
 * <p>{@link #record} is {@code MANDATORY}: called from inside the transaction that makes the
 * change, never on its own, so there is no change without its row and no row without its change.
 * A failed login is the exception, and says so. Reads of the log are not themselves audited --
 * what an admin looked at is the access log's job.
 */
@Service
public class AdminAuditService {

    private static final int MAX_PAGE = 100;

    private final AdminAuditRepository entries;
    private final AdminUserRepository admins;

    public AdminAuditService(AdminAuditRepository entries, AdminUserRepository admins) {
        this.entries = entries;
        this.admins = admins;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public AdminAuditEntry record(Long adminId, AdminAction action, String targetType, String targetId,
                                  String before, String after, String ipAddress) {
        return entries.save(new AdminAuditEntry(adminId, action, targetType, targetId, before, after, ipAddress));
    }

    /** Its own transaction: the login that failed has nothing else to commit, and the count
     *  of these is what locks an address out. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailedLogin(String email, String ipAddress, String reason) {
        entries.save(new AdminAuditEntry(null, AdminAction.LOGIN_FAILED, "admin", email, null, reason, ipAddress));
    }

    /** Newest first; every filter is optional. */
    @Transactional(readOnly = true)
    public Page_ search(Long adminId, AdminAction action, String targetType, String targetId, Instant from, Instant to,
                        int page, int size) {
        Page<AdminAuditEntry> found = entries.search(adminId, action, blank(targetType), blank(targetId), from, to,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE)));
        Map<Long, String> emails = admins.findAllById(found.stream().map(AdminAuditEntry::getAdminId)
                        .filter(Objects::nonNull).distinct().toList())
                .stream().collect(Collectors.toMap(AdminUser::getId, AdminUser::getEmail));
        return new Page_(found.stream().map(e -> new Entry(e.getId(), e.getAdminId(),
                e.getAdminId() == null ? null : emails.get(e.getAdminId()), e.getAction().name(),
                e.getTargetType(), e.getTargetId(), e.getBefore(), e.getAfter(), e.getIpAddress(), e.getCreatedAt()))
                .toList(), found.getTotalElements(), found.getNumber(), found.getSize());
    }

    /** The actions, for a filter that offers what exists rather than a text box. */
    public List<String> actions() {
        return Arrays.stream(AdminAction.values()).map(Enum::name).toList();
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
