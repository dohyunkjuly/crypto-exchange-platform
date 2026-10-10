package com.exchange.spot.stream;

import com.exchange.common.money.Money;
import com.exchange.contracts.spot.v1.BalanceChanged;

/**
 * One asset's balance, after a change.
 *
 * <p><b>Not a full account snapshot.</b> A lock moves one asset, a fill moves two, and a frame
 * carries only what changed -- an asset absent from it is unchanged, not zero. A client that
 * replaces its whole balance state from one of these will show every other asset as missing.
 *
 * <p>{@code version} is the row's own, and it is how a client orders the feed. Several
 * processes publish these, each after its own commit and off its own thread, so arrival order
 * is not write order: a lock announced by web-bff can reach a browser after the fill that
 * consumed it. The rule is to apply a frame only when its version is newer than the one held
 * for that <b>asset</b> -- per asset, never per account -- and to take the starting point from
 * {@code GET /bff/wallet/balances}, which returns the same number.
 *
 * <p>Money is a decimal string, never a JSON number -- the rule every view here follows.
 */
public record BalanceChangedData(String asset,
                                 String free,
                                 String locked,
                                 long version) {

    public static BalanceChangedData of(BalanceChanged change, String assetSymbol) {
        return new BalanceChangedData(assetSymbol,
                Money.toDecimal(change.getFree()),
                Money.toDecimal(change.getLocked()),
                change.getVersion());
    }
}
