package com.exchange.fee;

import com.exchange.common.money.Money;

/**
 * A resolved pair of rates, in signed basis points. 1 bps = 0.01%, so 0.1% is 10.
 *
 * <p>Negative maker means a rebate — what the exchange pays a market maker for providing
 * liquidity — which is why these are signed.
 *
 * <p>This is what the order path resolves once, at acceptance, and stamps onto the order.
 * Fills then charge the stamped rate and look nothing up.
 */
public record FeeRates(int makerBps, int takerBps) {

    public long makerFeeOn(long notional) {
        return Money.applyBps(notional, makerBps);
    }

    public long takerFeeOn(long notional) {
        return Money.applyBps(notional, takerBps);
    }
}
