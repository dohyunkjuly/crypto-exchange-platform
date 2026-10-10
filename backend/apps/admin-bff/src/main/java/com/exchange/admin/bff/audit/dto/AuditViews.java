package com.exchange.admin.bff.audit.dto;

import java.time.Instant;
import java.util.List;

/** The audit log's shapes. */
public final class AuditViews {

    private AuditViews() {
    }

    /** {@code adminEmail} is null for a failed login, which has no admin behind it. */
    public record Entry(Long id, Long adminId, String adminEmail, String action, String targetType,
                        String targetId, String before, String after, String ipAddress, Instant createdAt) {
    }

    public record Page_(List<Entry> items, long total, int page, int size) {
    }
}
