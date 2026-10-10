package com.exchange.bff.mfa.dto;

import com.exchange.user.mfa.dto.TotpEnrolment;

/**
 * What starting an enrolment hands back. Shown once and never retrievable -- starting
 * again mints a different secret and invalidates this one.
 *
 * @param secret          base32, for someone who cannot scan and types it in instead
 * @param provisioningUri the {@code otpauth://} URI a client renders as a QR code
 */
public record TotpEnrolmentView(String secret, String provisioningUri) {

    public static TotpEnrolmentView of(TotpEnrolment enrolment) {
        return new TotpEnrolmentView(enrolment.secret(), enrolment.provisioningUri());
    }
}
