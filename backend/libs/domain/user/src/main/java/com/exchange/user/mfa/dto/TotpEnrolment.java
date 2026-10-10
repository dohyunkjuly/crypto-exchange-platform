package com.exchange.user.mfa.dto;

/**
 * What starting a TOTP enrolment hands back, shown once and never retrievable again.
 *
 * @param secret          base32, for a user who cannot scan and types it in by hand
 * @param provisioningUri the {@code otpauth://} URI a client renders as a QR code
 */
public record TotpEnrolment(String secret, String provisioningUri) {
}
