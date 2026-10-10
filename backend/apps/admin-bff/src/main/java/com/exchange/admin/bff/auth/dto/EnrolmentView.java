package com.exchange.admin.bff.auth.dto;

/** Shown once, as a QR code. The seed is never returned again. */
public record EnrolmentView(String provisioningUri) {
}
