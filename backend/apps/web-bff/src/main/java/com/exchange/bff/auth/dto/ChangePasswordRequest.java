package com.exchange.bff.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** One proof is needed, and the server knows which: {@code mfaCode} when 2FA is on,
 *  {@code currentPassword} when not. The minimum matches registration. */
public record ChangePasswordRequest(
        @NotBlank @Size(min = 12) String newPassword,
        String currentPassword,
        String mfaCode) {
}
