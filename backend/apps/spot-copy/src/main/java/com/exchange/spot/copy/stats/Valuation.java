package com.exchange.spot.copy.stats;

import com.exchange.common.money.Money;
import com.exchange.copy.settings.CopyQuoteAsset;
import com.exchange.spot.symbol.SpotSymbolRepository;
import com.exchange.spot.trade.SpotTrade;
import com.exchange.spot.trade.SpotTradeRepository;
import com.exchange.wallet.balance.Balance;
import com.exchange.wallet.balance.BalanceRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * What an account is worth in the copy asset, for a leader's figures only: each holding at the
 * last trade of its market against the copy asset, and the copy asset as itself. A coin with
 * no such market, or one that has never traded, counts for nothing.
 *
 * <p>The one place a balance is valued at a market price. A return cannot be stated without
 * one; nothing is paid, charged or moved on what this says.
 */
@Component
public class Valuation {

    private final BalanceRepository balances;
    private final SpotSymbolRepository markets;
    private final SpotTradeRepository trades;
    private final CopyQuoteAsset quoteAsset;

    public Valuation(BalanceRepository balances, SpotSymbolRepository markets, SpotTradeRepository trades,
              CopyQuoteAsset quoteAsset) {
        this.balances = balances;
        this.markets = markets;
        this.trades = trades;
        this.quoteAsset = quoteAsset;
    }

    /** One run's prices, each asset looked up once however many accounts hold it. */
    Prices at(Instant when) {
        return new Prices(when);
    }

    public final class Prices {

        private final Instant when;
        private final Map<Long, Long> byAsset = new HashMap<>();

        private Prices(Instant when) {
            this.when = when;
        }

        /** Everything the account holds, available and locked. */
        long equity(Long userId) {
            long total = 0;
            for (Balance balance : balances.findByUserId(userId)) {
                total += worth(balance.getAssetId(), balance.getTotal());
            }
            return total;
        }

        long worth(Long assetId, long amount) {
            if (assetId.equals(quoteAsset.id())) {
                return amount;
            }
            long price = byAsset.computeIfAbsent(assetId, id -> markets.findByBaseAssetIdAndQuoteAssetId(id, quoteAsset.id())
                    .flatMap(market -> trades.lastBefore(market.getId(), when))
                    .map(SpotTrade::getPrice).orElse(0L));
            return price == 0 ? 0 : Money.multiply(price, amount);
        }
    }
}
