package com.exchange.fee;

/**
 * Which ladder a rate belongs to.
 *
 * <p>Spot and futures are separate schedules, not a discount on one schedule, and the
 * volume that earns each is counted separately — a user can be VIP 3 on spot and VIP 0 on
 * futures. Every fee table is keyed by this.
 */
public enum FeeProduct {
    SPOT,
    /** USD-margined perpetuals and futures. */
    USDM_FUTURES,
    /** Coin-margined. */
    COINM_FUTURES,
    MARGIN
}
