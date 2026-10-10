package com.exchange.admin.bff.ledger.dto;

import java.time.Instant;

/** The ledger screen's shapes. */
public final class LedgerViews {

    private LedgerViews() {
    }

    /** Amounts are signed decimal strings; {@code displayDecimals} is the asset's. */
    public record Row(String id, Long userId, String userEmail, String asset, int displayDecimals, String type,
                      String amount, String balanceAfter, String referenceType, String referenceId, Instant createdAt) {
    }
}
