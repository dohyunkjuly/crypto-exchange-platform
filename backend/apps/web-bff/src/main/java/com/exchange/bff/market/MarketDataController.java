package com.exchange.bff.market;

import com.exchange.bff.common.errors.BffHttpErrorCode;
import com.exchange.bff.market.dto.CandleView;
import com.exchange.bff.market.dto.DepthView;
import com.exchange.bff.symbol.dto.SymbolView;
import com.exchange.bff.market.dto.TradeView;
import com.exchange.common.money.Money;
import com.exchange.core.api.exception.ApiException;
import com.exchange.marketdata.candle.CandleInterval;
import com.exchange.marketdata.candle.CandleService;
import com.exchange.marketdata.candle.Market;
import com.exchange.marketdata.depth.DepthService;
import com.exchange.marketdata.tape.TradeTapeService;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The chart and the ticker.
 *
 * <p>Public: no {@code @CurrentUser}. What a market did is not anybody's private data, and
 * requiring a session to draw a chart would mean a logged-out visitor sees an empty page.
 *
 * <p>Reads only, and never from the trade tape -- {@code CandleService} and
 * {@code TickerService} live in {@code libs/domain/marketdata} and read candles the
 * {@code market-data} app already aggregated. This app has no idea Kafka exists.
 *
 * <p>{@link Market#SPOT} is hardcoded for now because that is the only product that
 * exists. When futures arrives it becomes a path segment rather than a second controller:
 * the services already take the market as an argument for exactly that reason.
 */
@RestController
@RequestMapping("/bff/market")
public class MarketDataController {

    /** Enough for any chart worth drawing, and low enough that a careless client cannot
     *  ask for a response measured in megabytes. {@code CandleService} clamps to its own
     *  maximum as well; this one is about the default. */
    private static final int DEFAULT_LIMIT = 500;

    /** What a trading page's visible ladder holds. Deliberately far below
     *  {@code DepthService.MAX_LEVELS}: the default should be the common case, not the
     *  ceiling, so a client that asks for nothing in particular gets a small response. */
    private static final int DEFAULT_DEPTH = 20;

    /** What a trades panel shows without scrolling. Below {@code TradeTapeService
     *  .MAX_TRADES}, so the default is the common case rather than the ceiling. */
    private static final int DEFAULT_TRADES = 50;

    private final CandleService candles;
    private final DepthService depth;
    private final TradeTapeService trades;

    public MarketDataController(CandleService candles, DepthService depth,
                                TradeTapeService trades) {
        this.candles = candles;
        this.depth = depth;
        this.trades = trades;
    }

    /**
     * The most recent trades, newest first.
     *
     * <p>What fills the trades panel when a page loads. The socket's {@code trade} channel
     * carries everything after that, and carries nothing before it -- a live feed pushes
     * what happens next, so without this the panel is empty until someone trades, which on
     * a quiet market looks exactly like a broken page.
     *
     * <p>Reads {@code market-data}'s public tape, never {@code spot_trades}: that table
     * records who traded with whom and what each paid, and none of it may reach a browser.
     *
     * <p>An empty list for a symbol that has never traded -- valid, not an error.
     */
    @GetMapping("/{symbol}/trades")
    public List<TradeView> trades(@PathVariable String symbol,
                                  @RequestParam(defaultValue = "" + DEFAULT_TRADES) int limit) {
        return trades.recent(Market.SPOT, symbol, clampTrades(limit)).stream()
                .map(TradeView::of)
                .toList();
    }



    /** Oldest first, which is the order a chart draws them in. {@code endTime} (ISO-8601) is
     *  the newest bucket wanted; absent means now. A chart scrolled into the past asks for the
     *  page ending just before its oldest bar, and stops when a page comes back short. */
    @GetMapping("/{symbol}/candles")
    public List<CandleView> candles(@PathVariable String symbol,
                                    @RequestParam(defaultValue = "1m") String interval,
                                    @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit,
                                    @RequestParam(required = false) Instant endTime) {
        // Filled at read time: a quiet bucket comes back flat, so the axis stays continuous.
        return candles.latestFilled(Market.SPOT, symbol, parse(interval), clamp(limit), endTime != null ? endTime : Instant.now()).stream()
                .map(CandleView::of)
                .toList();
    }


    /**
     * The order book, best price first on both sides.
     *
     * <p>Reads what {@code market-data} folded out of the event stream -- see {@code
     * DepthWriter}. Asking past the precomputed top of book is allowed and simply costs
     * more, which is {@code DepthService}'s decision to make, not this controller's.
     *
     * <p>{@code grouping} collapses the ladder into price buckets, as the selector on a
     * trading screen does; absent means the raw book. Grouping reads the whole stored
     * ladder rather than the precomputed top, and has to -- a bucket is a price range, and
     * twenty rows at a coarse grouping can span more of the book than any fixed number of
     * levels would cover. The valid values for a market come from
     * {@code /bff/symbols}.
     *
     * <p>A symbol with no book yet returns an empty ladder rather than a 404, unlike
     * {@link #ticker}: see {@link DepthView#empty}.
     */
    @GetMapping("/{symbol}/depth")
    public DepthView depth(@PathVariable String symbol,
                           @RequestParam(defaultValue = "" + DEFAULT_DEPTH) int limit,
                           @RequestParam(required = false) String grouping) {
        int levels = clampDepth(limit);
        return (grouping == null || grouping.isBlank()
                ? depth.of(Market.SPOT, symbol, levels)
                : depth.grouped(Market.SPOT, symbol, parseGrouping(grouping), levels))
                .map(DepthView::of)
                .orElseGet(() -> DepthView.empty(symbol));
    }

    /** {@code Money.toWire} refuses more precision than the scale holds rather than
     *  rounding it away, so an unreadable grouping is this app's error to name -- the same
     *  translation {@link #parse} does for a candle interval. */
    private static long parseGrouping(String grouping) {
        try {
            long scaled = Money.toWire(grouping);
            if (scaled <= 0) {
                throw new IllegalArgumentException("grouping must be positive");
            }
            return scaled;
        } catch (ArithmeticException | IllegalArgumentException e) {
            throw new ApiException(BffHttpErrorCode.INVALID_DEPTH_GROUPING);
        }
    }

    /** {@code CandleInterval} lives in a shared module, so it throws a plain
     *  {@code IllegalArgumentException} with no error code attached -- per
     *  AGENTS.md, translating that into this app's own contract is this app's
     *  job. Done here rather than in an advice because catching bare
     *  {@code IllegalArgumentException} app-wide would swallow far more than this. */
    private static CandleInterval parse(String interval) {
        try {
            return CandleInterval.ofCode(interval);
        } catch (IllegalArgumentException e) {
            throw new ApiException(BffHttpErrorCode.INVALID_CANDLE_INTERVAL);
        }
    }

    private static int clamp(int limit) {
        if (limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, CandleService.MAX_CANDLES);
    }

    /** Same shape and same reasoning as {@link #clampDepth}. */
    private static int clampTrades(int limit) {
        return limit <= 0 ? DEFAULT_TRADES : Math.min(limit, TradeTapeService.MAX_TRADES);
    }

    /** Same shape as {@link #clamp}, and clamping rather than rejecting for the same
     *  reason: a limit is a request for how much, not an assertion, so the sane answer to
     *  an absurd one is the most we will give. {@code DepthService} caps the top end
     *  itself; this only turns a missing or nonsensical value into the default, since it
     *  would otherwise throw. */
    private static int clampDepth(int limit) {
        return limit <= 0 ? DEFAULT_DEPTH : Math.min(limit, DepthService.MAX_LEVELS);
    }
}
