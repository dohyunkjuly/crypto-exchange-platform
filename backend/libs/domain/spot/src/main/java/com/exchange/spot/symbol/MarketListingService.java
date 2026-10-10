package com.exchange.spot.symbol;

import com.exchange.wallet.asset.Asset;
import com.exchange.wallet.asset.AssetRepository;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Listing a market and running it, for staff (docs/reference/admin.md). Framework-neutral about
 * who asked: the audit row is the caller's to write, in the same transaction.
 *
 * <p>Nothing else has to be told. The engine makes a market's book on its first command, order
 * placement reads the row on every order, and the apps' market lists refresh within a minute.
 *
 * <p><b>Fixed at listing, never changed:</b> the pair, the command partition
 * (docs/reference/command-partition.md), the price tick and the quantity step -- resting orders
 * and every client's rounding were made to them. Only the limits around them are edited.
 *
 * <p>Staff move a market between {@code TRADING} and {@code CANCEL_ONLY}, and open a new one from
 * {@code PRE_TRADING}. {@code HALTED} would do exactly what cancel-only does, and delisting is not
 * a staff action, so neither is offered.
 */
@Service
public class MarketListingService {

    /** What staff may set a market to. */
    public static final Set<SymbolStatus> SETTABLE = Set.of(SymbolStatus.TRADING, SymbolStatus.CANCEL_ONLY);

    /** Fixed for the market's life, and so given once. Amounts are 1e8-scaled. */
    public record NewMarket(Long baseAssetId, Long quoteAssetId, int commandPartition,
                            long priceTick, long qtyStep, Limits limits) {
    }

    /** The limits staff may change on a listed market. Amounts are 1e8-scaled; null is "no limit". */
    public record Limits(long minQty, long maxQty, long minNotional, Long maxNotional,
                         Long marketMinQty, Long marketMaxQty, Long minQuoteQty, Long maxQuoteQty,
                         Integer priceBandBps, int maxOpenOrders, int maxOpenConditionalOrders,
                         boolean marketOrdersEnabled, boolean stopOrdersEnabled) {
    }

    private final SpotSymbolRepository markets;
    private final AssetRepository assets;

    public MarketListingService(SpotSymbolRepository markets, AssetRepository assets) {
        this.markets = markets;
        this.assets = assets;
    }

    /**
     * Listed in {@code PRE_TRADING}: nothing trades until someone opens it.
     *
     * @param partitionCount how many partitions the engine's commands topic has
     * @throws IllegalArgumentException an unknown asset, a pair of one asset, a partition the
     *                                  engine does not have, or sizing that could never take an order
     * @throws IllegalStateException the pair is already listed
     */
    @Transactional
    public SpotSymbol list(NewMarket request, int partitionCount) {
        Asset base = asset(request.baseAssetId());
        Asset quote = asset(request.quoteAssetId());
        if (base.getId().equals(quote.getId())) {
            throw new IllegalArgumentException("a market needs two different assets");
        }
        if (request.commandPartition() < 0 || request.commandPartition() >= partitionCount) {
            throw new IllegalArgumentException("the engine has partitions 0 to " + (partitionCount - 1));
        }
        String symbol = base.getSymbol() + quote.getSymbol();
        if (symbol.length() > 20) {
            throw new IllegalArgumentException("the market's name would be longer than 20 characters");
        }
        if (markets.findByBaseAssetIdAndQuoteAssetId(base.getId(), quote.getId()).isPresent()
                || markets.findBySymbol(symbol).isPresent()) {
            throw new IllegalStateException(symbol + " is already listed");
        }
        positive(request.priceTick(), "the price tick");
        positive(request.qtyStep(), "the quantity step");
        check(request.limits(), request.qtyStep());

        SpotSymbol market = new SpotSymbol(symbol, base.getId(), quote.getId(), request.priceTick(),
                request.qtyStep(), request.limits().minQty(), request.limits().maxQty(),
                request.limits().minNotional(), request.commandPartition());
        apply(market, request.limits());
        return markets.save(market);
    }

    /**
     * @throws IllegalArgumentException a status staff do not set, or one this market cannot move to
     * @throws IllegalStateException it is already in that status
     */
    @Transactional
    public SpotSymbol setStatus(Long marketId, SymbolStatus target) {
        SpotSymbol market = market(marketId);
        if (!SETTABLE.contains(target)) {
            throw new IllegalArgumentException("staff set a market to TRADING or CANCEL_ONLY only");
        }
        SymbolStatus from = market.getStatus();
        if (from == target) {
            throw new IllegalStateException("already " + target);
        }
        if (from == SymbolStatus.PRE_TRADING && target != SymbolStatus.TRADING) {
            throw new IllegalArgumentException("a new market can only be opened");
        }
        if (from == SymbolStatus.POST_TRADING || from == SymbolStatus.DELISTED) {
            throw new IllegalArgumentException("a " + from + " market is not run from here");
        }
        market.setStatus(target);
        return markets.save(market);
    }

    /** @throws IllegalArgumentException limits that contradict each other or the market's step */
    @Transactional
    public SpotSymbol setLimits(Long marketId, Limits limits) {
        SpotSymbol market = market(marketId);
        check(limits, market.getQtyStep());
        apply(market, limits);
        return markets.save(market);
    }

    /** @throws IllegalArgumentException no such market */
    public SpotSymbol market(Long marketId) {
        return markets.findById(marketId).orElseThrow(() -> new MarketNotFound(marketId));
    }

    /** No such market: kept apart from the other refusals so the caller can answer 404. */
    public static final class MarketNotFound extends IllegalArgumentException {
        MarketNotFound(Long id) {
            super("no market " + id);
        }
    }

    private Asset asset(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("both assets are required");
        }
        return assets.findById(id).orElseThrow(() -> new IllegalArgumentException("no asset " + id));
    }

    /** Every order a market takes is sized to its step, so a bound off the step is one no order can meet. */
    private static void check(Limits l, long qtyStep) {
        positive(l.minQty(), "the minimum quantity");
        onStep(l.minQty(), qtyStep, "the minimum quantity");
        onStep(l.maxQty(), qtyStep, "the maximum quantity");
        if (l.maxQty() < l.minQty()) {
            throw new IllegalArgumentException("the maximum quantity is below the minimum");
        }
        positive(l.minNotional(), "the minimum order value");
        if (l.maxNotional() != null && l.maxNotional() < l.minNotional()) {
            throw new IllegalArgumentException("the maximum order value is below the minimum");
        }
        pair(l.marketMinQty(), l.marketMaxQty(), "market-order quantity");
        if (l.marketMinQty() != null) {
            onStep(l.marketMinQty(), qtyStep, "the minimum market-order quantity");
        }
        if (l.marketMaxQty() != null) {
            onStep(l.marketMaxQty(), qtyStep, "the maximum market-order quantity");
        }
        pair(l.minQuoteQty(), l.maxQuoteQty(), "market-buy spend");
        if (l.priceBandBps() != null && (l.priceBandBps() < 1 || l.priceBandBps() > 10_000)) {
            throw new IllegalArgumentException("the price band is 1 to 10000 bps");
        }
        if (l.maxOpenOrders() < 1 || l.maxOpenConditionalOrders() < 0) {
            throw new IllegalArgumentException("a market allows at least one open order");
        }
    }

    private static void pair(Long min, Long max, String what) {
        if (min != null) {
            positive(min, "the minimum " + what);
        }
        if (max != null) {
            positive(max, "the maximum " + what);
        }
        if (min != null && max != null && max < min) {
            throw new IllegalArgumentException("the maximum " + what + " is below the minimum");
        }
    }

    private static void positive(long value, String what) {
        if (value <= 0) {
            throw new IllegalArgumentException(what + " must be above zero");
        }
    }

    private static void onStep(long value, long step, String what) {
        if (value % step != 0) {
            throw new IllegalArgumentException(what + " is not a multiple of the quantity step");
        }
    }

    private static void apply(SpotSymbol m, Limits l) {
        m.setMinQty(l.minQty());
        m.setMaxQty(l.maxQty());
        m.setMinNotional(l.minNotional());
        m.setMaxNotional(l.maxNotional());
        m.setMarketMinQty(l.marketMinQty());
        m.setMarketMaxQty(l.marketMaxQty());
        m.setMinQuoteQty(l.minQuoteQty());
        m.setMaxQuoteQty(l.maxQuoteQty());
        m.setPriceBandBps(l.priceBandBps());
        m.setMaxOpenOrders(l.maxOpenOrders());
        m.setMaxOpenConditionalOrders(l.maxOpenConditionalOrders());
        m.setMarketOrdersEnabled(l.marketOrdersEnabled());
        m.setStopOrdersEnabled(l.stopOrdersEnabled());
    }
}
