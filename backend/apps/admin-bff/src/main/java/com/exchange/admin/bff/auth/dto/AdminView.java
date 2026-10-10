package com.exchange.admin.bff.auth.dto;

import com.exchange.admin.identity.AdminRole;
import com.exchange.admin.identity.AdminUser;
import java.time.Instant;

/** {@code totpEnrolled} is what a SUPERADMIN needs to see before resetting it; nothing about the seed itself. */
public record AdminView(Long id, String email, AdminRole role, boolean totpEnrolled, Instant disabledAt, Instant createdAt) {

    public static AdminView of(AdminUser admin) {
        return new AdminView(admin.getId(), admin.getEmail(), admin.getRole(), admin.isTotpConfirmed(),
                admin.getDisabledAt(), admin.getCreatedAt());
    }
}
