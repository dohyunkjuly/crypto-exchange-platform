package com.exchange.admin.bff.dashboard;

import com.exchange.admin.bff.dashboard.dto.DashboardViews.Compare;
import com.exchange.admin.bff.dashboard.dto.DashboardViews.Dashboard;
import com.exchange.admin.bff.dashboard.dto.DashboardViews.Pair;
import com.exchange.common.money.Money;
import com.exchange.spot.symbol.SpotSymbol;
import com.exchange.spot.symbol.SpotSymbolRepository;
import com.exchange.spot.trade.SpotTradeRepository;
import com.exchange.user.identity.UserRepository;
import com.exchange.user.identity.UserType;
import com.exchange.wallet.asset.Asset;
import com.exchange.wallet.asset.AssetService;
import com.exchange.wallet.treasury.TreasuryQueryService;
import com.exchange.wallet.treasury.TreasuryQueryService.FeeFlow;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * The console's home: what today looks like so far, against yesterday.
 *
 * <p><b>Yesterday means yesterday up to this time of day</b>, not all of it. Against a whole day,
 * every morning would read as a collapse and every evening as a record, and the number would say
 * more about the clock than about the exchange.
 *
 * <p>Each figure comes from where it is kept: signups from the user rows, trades and volume from
 * the trades, fees from the ledger through the treasury service -- so the dashboard and the
 * treasury screen can never disagree about what was earned. Days are UTC, as the ledger is.
 * Any admin may read it.
 */
@Service
public class DashboardService {

    /** The quote every market here is priced in (the seed builds them as base + USDT). Volume is
     *  summed across markets only in this asset; a market quoted in anything else is listed with
     *  its own quote and left out of the total, never converted into it. */
    private static final String QUOTE = "USDT";

    private final UserRepository users;
    private final SpotTradeRepository trades;
    private final SpotSymbolRepository symbols;
    private final AssetService assets;
    private final TreasuryQueryService treasury;

    public DashboardService(UserRepository users, SpotTradeRepository trades, SpotSymbolRepository symbols,
                            AssetService assets, TreasuryQueryService treasury) {
        this.users = users;
        this.trades = trades;
        this.symbols = symbols;
        this.assets = assets;
        this.treasury = treasury;
    }

    public Dashboard get() {
        Instant now = Instant.now();
        Instant today = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant yesterday = today.minus(Duration.ofDays(1));
        Instant sameTimeYesterday = now.minus(Duration.ofDays(1));

        long signupsToday = users.countByTypeAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(UserType.PRIMARY, today, now);
        long signupsYesterday = users.countByTypeAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                UserType.PRIMARY, yesterday, sameTimeYesterday);

        List<SpotSymbol> markets = symbols.findAll();
        Map<Long, Asset> quotes = assets.listByIds(markets.stream().map(SpotSymbol::getQuoteAssetId).distinct().toList())
                .stream().collect(Collectors.toMap(Asset::getId, Function.identity()));

        List<Pair> pairs = new ArrayList<>();
        long volumeToday = 0;
        long volumeYesterday = 0;
        long tradesToday = 0;
        long tradesYesterday = 0;
        for (SpotSymbol market : markets) {
            long[] t = volume(market.getId(), today, now);
            long[] y = volume(market.getId(), yesterday, sameTimeYesterday);
            tradesToday += t[1];
            tradesYesterday += y[1];
            Asset quote = quotes.get(market.getQuoteAssetId());
            String quoteSymbol = quote == null ? "?" : quote.getSymbol();
            if (QUOTE.equals(quoteSymbol)) {
                volumeToday += t[0];
                volumeYesterday += y[0];
            }
            // A market that has not traded in either window adds a row of zeros and nothing else.
            if (t[1] > 0 || y[1] > 0) {
                pairs.add(new Pair(market.getSymbol(), quoteSymbol, Money.toDecimal(t[0]), Money.toDecimal(y[0]), t[1]));
            }
        }
        pairs.sort(Comparator.comparing((Pair p) -> Money.toWire(p.volumeToday())).reversed());

        return new Dashboard(now,
                new Compare(Long.toString(signupsToday), Long.toString(signupsYesterday)),
                new Compare(Long.toString(tradesToday), Long.toString(tradesYesterday)),
                new Compare(Money.toDecimal(volumeToday), Money.toDecimal(volumeYesterday)),
                new Compare(usdtFees(today, now), usdtFees(yesterday, sameTimeYesterday)),
                pairs);
    }

    /** {sum of quote quantity, trade count} for one market over [from, to). */
    private long[] volume(Long symbolId, Instant from, Instant to) {
        Object[] row = trades.volumeBetween(symbolId, from, to).get(0);
        return new long[] {((Number) row[0]).longValue(), ((Number) row[1]).longValue()};
    }

    /** What customers paid in USDT fees, net of rebates, as the treasury screen counts it. */
    private String usdtFees(Instant from, Instant to) {
        Long usdt = assets.getBySymbol(QUOTE).getId();
        return treasury.feeFlowSince(from, to).stream()
                .filter(f -> f.assetId().equals(usdt))
                .map(FeeFlow::fees)
                .findFirst()
                .map(Money::toDecimal)
                .orElse("0");
    }
}
