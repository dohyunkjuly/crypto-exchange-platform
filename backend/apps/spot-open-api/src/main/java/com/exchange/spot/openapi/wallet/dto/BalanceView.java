package com.exchange.spot.openapi.wallet.dto;

import com.exchange.common.money.Money;
import com.exchange.wallet.asset.Asset;
import com.exchange.wallet.balance.Balance;

/** Open-api's copy of web-bff's balance row. {@code version} is the row's own, monotonic per
 *  asset: a client applies a pushed balance change only if its version is newer, and this is
 *  the starting point. Amounts are decimal strings, never JSON numbers. */
public record BalanceView(String asset, String available, String locked, String total, long version) {
    public static BalanceView of(Balance balance, Asset asset) {
        return new BalanceView(asset.getSymbol(), Money.toDecimal(balance.getAvailable()),
                Money.toDecimal(balance.getLocked()), Money.toDecimal(balance.getTotal()),
                balance.getVersion());
    }
}
