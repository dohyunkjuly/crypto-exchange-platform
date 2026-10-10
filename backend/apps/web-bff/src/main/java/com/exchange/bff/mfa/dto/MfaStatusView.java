package com.exchange.bff.mfa.dto;

import java.time.Instant;

/**
 * Whether a second factor is active, and how much recovery is left.
 *
 * <p>{@code recoveryCodesRemaining} is here so a client can warn before someone runs out
 * -- discovering you have none the day you lose your phone is the failure this whole
 * mechanism exists to prevent. {@code enabledAt} is when the active factor was confirmed, null
 * when none is.
 */
public record MfaStatusView(boolean enabled, long recoveryCodesRemaining, Instant enabledAt) {
}
