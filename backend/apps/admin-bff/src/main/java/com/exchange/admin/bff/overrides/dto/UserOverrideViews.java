package com.exchange.admin.bff.overrides.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/** The "Fees & limits" tab's shapes: a customer's overrides and the defaults they replace. */
public final class UserOverrideViews {

    private UserOverrideViews() {
    }

    /** A fee override as staff read it, in bps. {@code effective} is false once it has expired. */
    public record FeeGrant(int makerFeeBps, int takerFeeBps, String reason, Instant expiresAt, boolean effective,
                           String grantedBy, Instant updatedAt) {
    }

    /** A cap override: open orders and open stop orders, either possibly null. */
    public record LimitGrant(Integer maxOrders, Integer maxStops, String reason, Instant expiresAt, boolean effective,
                             String grantedBy, Instant updatedAt) {
    }

    /**
     * What the customer pays now and why: {@code OVERRIDE} or {@code TIER}. A sub-account pays its
     * master's fee, so for a sub both -- and {@code override} -- are the master's.
     */
    public record FeeView(int makerFeeBps, int takerFeeBps, String source, Integer tierLevel, String tierName,
                          boolean subAccount, FeeGrant override) {
    }

    /** A cap and the default it replaces; {@code symbol} is null for the account-wide one. */
    public record LimitView(String symbol, int defaultOrders, int defaultStops, LimitGrant own) {
    }

    public record Overrides(FeeView fee, LimitView orderLimit, List<LimitView> marketLimits) {
    }

    public record FeeBody(int makerFeeBps, int takerFeeBps, @NotBlank @Size(max = 255) String reason, Instant expiresAt) {
    }

    public record LimitBody(String symbol, Integer maxOrders, Integer maxStops, @NotBlank @Size(max = 255) String reason,
                            Instant expiresAt) {
    }
}
