package com.exchange.spot.symbol;

import com.exchange.marketdata.depth.DepthGroupings;
import com.exchange.marketdata.depth.PriceGrouping;
import com.exchange.wallet.asset.Asset;
import com.exchange.wallet.asset.AssetRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The markets a client may see, with their assets resolved to codes -- held in memory and
 * refreshed on a timer.
 *
 * <p>Shared by web-bff and spot-open-api (docs/STYLE.md: one consumer, it lives there;
 * two, it moves here). Needs {@code @EnableScheduling} in whichever app holds it, or the
 * listing is read once at startup and never again.
 *
 * <p><b>Cached because of how it is read.</b> A listing changes when an operator lists or
 * halts a market, which is to say almost never, while this is consulted on every
 * subscription and on every ticker refresh. Reading MySQL for each would be two queries to
 * answer a question whose answer was the same all day. The cost is that a newly listed
 * market takes up to one refresh interval to appear, which is the right trade for something
 * an operator does by hand.
 */
@Component
public class SymbolCatalog implements DepthGroupings {

    private static final Logger log = LoggerFactory.getLogger(SymbolCatalog.class);

    /**
     * A market is visible once it has been open and until it is gone.
     *
     * <p>{@code HALTED} and {@code CANCEL_ONLY} are included on purpose: a market that
     * stops trading must keep appearing, showing why, or a user holding a position in it
     * watches the symbol vanish and cannot tell an outage from a delisting.
     * {@code PRE_TRADING} is excluded because it has never opened, and {@code DELISTED}
     * because it never will again.
     */
    private static final Set<SymbolStatus> VISIBLE = EnumSet.of(
            SymbolStatus.TRADING, SymbolStatus.HALTED,
            SymbolStatus.CANCEL_ONLY, SymbolStatus.POST_TRADING);

    private final SpotSymbolRepository symbols;
    private final AssetRepository assets;

    /** Replaced wholesale, never mutated, so a reader always sees one consistent listing.
     *  Insertion-ordered, alphabetically, so a selector renders predictably. */
    private volatile Map<String, MarketSymbol> snapshot = Map.of();

    public SymbolCatalog(SpotSymbolRepository symbols, AssetRepository assets) {
        this.symbols = symbols;
        this.assets = assets;
        // Eagerly, not on the first scheduled tick: something asking during startup must
        // not see an empty exchange.
        refresh();
    }

    /** Every visible market, alphabetically. */
    public List<MarketSymbol> visible() {
        return List.copyOf(snapshot.values());
    }

    /** One market, or empty if it is not listed or not visible. */
    public Optional<MarketSymbol> find(String symbol) {
        return Optional.ofNullable(snapshot.get(symbol));
    }

    /** What the depth channel checks a grouping against; see {@link DepthGroupings}. */
    @Override
    public Optional<List<Long>> groupingsOf(String symbol) {
        return find(symbol).map(MarketSymbol::depthGroupings);
    }

    @Scheduled(fixedDelayString = "${exchange.reference.refresh-ms:60000}")
    public final void refresh() {
        try {
            snapshot = build();
        } catch (RuntimeException e) {
            // Keep serving the previous listing: a stale market list beats none, and a
            // listing changes so rarely that "stale" almost always means "identical".
            log.warn("could not refresh the symbol catalog; serving the previous one", e);
        }
    }

    private Map<String, MarketSymbol> build() {
        // findAll() rather than a lookup per symbol: a handful of assets, dozens of
        // markets, so this is one query instead of N.
        Map<Long, Asset> byId = assets.findAll().stream()
                .collect(Collectors.toMap(Asset::getId, Function.identity()));

        Map<String, MarketSymbol> built = new LinkedHashMap<>();
        symbols.findAll().stream()
                .filter(symbol -> VISIBLE.contains(symbol.getStatus()))
                .sorted(Comparator.comparing(SpotSymbol::getSymbol))
                .forEach(symbol -> {
                    Asset base = byId.get(symbol.getBaseAssetId());
                    Asset quote = byId.get(symbol.getQuoteAssetId());
                    // A market whose asset row is missing is a broken listing, not
                    // something to render half of. Dropping it keeps one bad row out of
                    // every client's list.
                    if (base != null && quote != null) {
                        built.put(symbol.getSymbol(), toMarket(symbol, base, quote));
                    }
                });
        return Map.copyOf(built);
    }

    private static MarketSymbol toMarket(SpotSymbol symbol, Asset base, Asset quote) {
        return new MarketSymbol(symbol.getSymbol(), base.getSymbol(), quote.getSymbol(),
                symbol.getStatus(), symbol.getPriceTick(), symbol.getQtyStep(),
                symbol.getMinQty(), symbol.getMaxQty(), symbol.getMinNotional(),
                symbol.getMarketMinQty(), symbol.getMarketMaxQty(), symbol.getMaxNotional(),
                symbol.getMinQuoteQty(), symbol.getMaxQuoteQty(),
                base.getDisplayDecimals(), quote.getDisplayDecimals(),
                symbol.isMarketOrdersEnabled(), symbol.isStopOrdersEnabled(),
                groupingsOf(symbol), symbol.getCommandPartition());
    }

    /**
     * The configured groupings, or ones derived from the tick when there are none.
     *
     * <p>Falls back rather than failing, in both directions: an unconfigured market and a
     * market configured with something unusable both end up with a working selector. A
     * bad value is logged because it is an operator mistake worth fixing, but it must not
     * take the order book off the screen -- the column is presentation config, and no
     * value of it justifies a market that cannot be looked at.
     */
    private static List<Long> groupingsOf(SpotSymbol symbol) {
        String configured = symbol.getDepthGroupings();
        if (configured == null || configured.isBlank()) {
            return PriceGrouping.derivedFrom(symbol.getPriceTick());
        }
        List<Long> parsed = new ArrayList<>();
        try {
            for (String value : configured.split(",")) {
                parsed.add(Long.parseLong(value.trim()));
            }
        } catch (NumberFormatException e) {
            log.warn("{} has unparseable depth_groupings '{}'; deriving from the tick",
                    symbol.getSymbol(), configured);
            return PriceGrouping.derivedFrom(symbol.getPriceTick());
        }
        if (!PriceGrouping.areValidFor(parsed, symbol.getPriceTick())) {
            log.warn("{} has depth_groupings '{}' that are not ascending whole multiples of "
                    + "its {} tick; deriving from the tick",
                    symbol.getSymbol(), configured, symbol.getPriceTick());
            return PriceGrouping.derivedFrom(symbol.getPriceTick());
        }
        return List.copyOf(parsed);
    }
}
