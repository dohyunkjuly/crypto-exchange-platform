package com.exchange.bff.wallet.dto;

import com.exchange.common.money.Money;
import com.exchange.wallet.asset.Asset;
import com.exchange.wallet.balance.Balance;

/** Amounts are decimal strings, never JSON numbers -- see {@link Money}. */
/**
 * {@code version} is the balance row's own, and it is what lets a client combine this snapshot
 * with the live feed. Several processes announce balance changes on
 * {@code exchange.spot.settlements.v1}, each after its own commit and off its own thread, so
 * the order they arrive is not the order they happened. A client applies a pushed change only
 * if its version is newer than what it holds -- and without this field it has no starting
 * point, so the first push after a reconnect is either wrongly dropped or wrongly applied.
 *
 * <p>Monotonic per asset, not per account: compare a USDT version only against USDT's. It
 * comes from the database rather than a sender, which is why it survives three producers and
 * clock skew between them.
 *
 * <p>Same handshake the order book needs, for the same reason -- subscribe first, then fetch
 * this, then discard anything not newer. See {@code Depth.lastSequence}.
 */
public record BalanceView(String asset, String available, String locked, String total,
                          long version) {

    public static BalanceView of(Balance balance, Asset asset) {
        return new BalanceView(asset.getSymbol(), Money.toDecimal(balance.getAvailable()),
                Money.toDecimal(balance.getLocked()), Money.toDecimal(balance.getTotal()),
                balance.getVersion());
    }
}
