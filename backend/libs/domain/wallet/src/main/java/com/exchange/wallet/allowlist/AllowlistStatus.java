package com.exchange.wallet.allowlist;

public enum AllowlistStatus {
    /** Added, but inside the cooling period and not yet usable. */
    PENDING,
    ACTIVE,
    REMOVED
}
