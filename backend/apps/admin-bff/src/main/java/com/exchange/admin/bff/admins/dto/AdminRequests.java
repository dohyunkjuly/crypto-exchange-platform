package com.exchange.admin.bff.admins.dto;

import com.exchange.admin.identity.AdminRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** What a SUPERADMIN sends to manage staff. */
public final class AdminRequests {

    private AdminRequests() {
    }

    public record CreateRequest(@NotBlank @Email String email, @NotBlank String password, @NotNull AdminRole role) {
    }

    public record RoleRequest(@NotNull AdminRole role) {
    }
}
