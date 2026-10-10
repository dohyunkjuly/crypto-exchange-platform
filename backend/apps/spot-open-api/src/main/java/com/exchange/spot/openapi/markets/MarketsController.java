package com.exchange.spot.openapi.markets;

import com.exchange.common.money.Money;
import com.exchange.core.api.exception.ApiException;
import com.exchange.marketdata.candle.CandleInterval;
import com.exchange.marketdata.candle.CandleService;
import com.exchange.marketdata.candle.Market;
import com.exchange.marketdata.depth.DepthService;
import com.exchange.marketdata.tape.TradeTapeService;
import com.exchange.marketdata.ticker.TickerService;
import com.exchange.spot.openapi.common.errors.OpenApiErrorCode;
import com.exchange.spot.openapi.common.ratelimit.Weight;
import com.exchange.spot.openapi.markets.dto.CandleView;
import com.exchange.spot.openapi.markets.dto.DepthView;
import com.exchange.spot.openapi.markets.dto.MarketsInfoView;
import com.exchange.spot.openapi.markets.dto.MarketsInfoView.RateLimitView;
import com.exchange.spot.openapi.markets.dto.SymbolView;
import com.exchange.spot.openapi.markets.dto.TickerView;
import com.exchange.spot.openapi.markets.dto.TradeView;
import com.exchange.spot.symbol.EnginePresence;
import com.exchange.spot.symbol.SymbolCatalog;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /v1/markets}: what is listed and what each market did. Public -- what a market did is
 * nobody's private data -- and listed as such in {@code WebMvcConfig}.
 *
 * <p>Thin over the shared marketdata services; the one job here is translating their plain
 * exceptions into this app's codes, per AGENTS.md (a shared module owns no error code).
 */
@RestController
@RequestMapping("/v1/markets")
public class MarketsController {

    private static final int DEFAULT_CANDLES = 500;
    private static final int DEFAULT_DEPTH = 50;
    private static final int DEFAULT_TRADES = 50;

    private final SymbolCatalog catalog;
    private final EnginePresence engines;
    private final TickerCache allTickers;
    private final TickerService tickers;
    private final DepthService depth;
    private final TradeTapeService trades;
    private final CandleService candles;
    private final Clock clock;
    private final List<RateLimitView> rateLimits;

    public MarketsController(SymbolCatalog catalog, EnginePresence engines, TickerCache allTickers, TickerService tickers,
                             DepthService depth, TradeTapeService trades, CandleService candles, Clock clock,
                             @Value("${exchange.rate-limit.request-weight-per-minute}") int weightPerMinute,
                             @Value("${exchange.rate-limit.orders-per-ten-seconds}") int ordersPerTenSeconds,
                             @Value("${exchange.rate-limit.orders-per-day}") int ordersPerDay) {
        this.catalog = catalog;
        this.engines = engines;
        this.allTickers = allTickers;
        this.tickers = tickers;
        this.depth = depth;
        this.trades = trades;
        this.candles = candles;
        this.clock = clock;
        this.rateLimits = List.of(new RateLimitView("REQUEST_WEIGHT", "1m", weightPerMinute),
                new RateLimitView("ORDERS", "10s", ordersPerTenSeconds),
                new RateLimitView("ORDERS", "1d", ordersPerDay));
    }

    /** Every visible market with its order rules, and the limits this API enforces. */
    @GetMapping("/info")
    @Weight(10)
    public MarketsInfoView info() {
        return new MarketsInfoView(catalog.visible().stream()
                .map(m -> SymbolView.of(m, !engines.isLive(m.commandPartition()))).toList(), rateLimits);
    }

    @GetMapping("/tickers")
    @Weight(20)
    public List<TickerView> tickers() {
        return allTickers.all().stream().map(TickerView::of).toList();
    }

    /** Computed on demand, not from the cache: one symbol is one query, and a caller asking for
     *  exactly one wants it current. {@code NO_MARKET_DATA}, not {@code SYMBOL_NOT_FOUND}, for a
     *  listed market that has never traded. */
    @GetMapping("/{symbol}/ticker")
    public TickerView ticker(@PathVariable String symbol) {
        requireListed(symbol);
        return tickers.of(Market.SPOT, symbol).map(TickerView::of)
                .orElseThrow(() -> new ApiException(OpenApiErrorCode.NO_MARKET_DATA));
    }

    @GetMapping("/{symbol}/depth")
    @Weight(value = 1, perLimit = 100)
    public DepthView depth(@PathVariable String symbol,
                           @RequestParam(defaultValue = "" + DEFAULT_DEPTH) int limit,
                           @RequestParam(required = false) String grouping) {
        requireListed(symbol);
        int levels = limit <= 0 ? DEFAULT_DEPTH : Math.min(limit, DepthService.MAX_LEVELS);
        return (grouping == null || grouping.isBlank()
                ? depth.of(Market.SPOT, symbol, levels)
                : depth.grouped(Market.SPOT, symbol, parseGrouping(grouping), levels))
                .map(DepthView::of)
                .orElseGet(() -> DepthView.empty(symbol));
    }

    /** Newest first. */
    @GetMapping("/{symbol}/trades")
    public List<TradeView> trades(@PathVariable String symbol,
                                  @RequestParam(defaultValue = "" + DEFAULT_TRADES) int limit) {
        requireListed(symbol);
        int n = limit <= 0 ? DEFAULT_TRADES : Math.min(limit, TradeTapeService.MAX_TRADES);
        return trades.recent(Market.SPOT, symbol, n).stream().map(TradeView::of).toList();
    }

    /** Oldest first, quiet buckets flat. {@code endTime} is the newest bucket wanted; absent means
     *  now. A page shorter than {@code limit} is the start of the market's history. */
    @GetMapping("/{symbol}/candles")
    public List<CandleView> candles(@PathVariable String symbol,
                                    @RequestParam(defaultValue = "1m") String interval,
                                    @RequestParam(defaultValue = "" + DEFAULT_CANDLES) int limit,
                                    @RequestParam(required = false) Instant endTime) {
        requireListed(symbol);
        int n = limit <= 0 ? DEFAULT_CANDLES : Math.min(limit, CandleService.MAX_CANDLES);
        return candles.latestFilled(Market.SPOT, symbol, parseInterval(interval), n,
                        endTime != null ? endTime : clock.instant())
                .stream().map(CandleView::of).toList();
    }

    /** Unlike web-bff, an unlisted symbol is refused up front: a bot that mistypes a symbol should
     *  hear so, not read an empty book and conclude the market is quiet. */
    private void requireListed(String symbol) {
        if (catalog.find(symbol).isEmpty()) {
            throw new ApiException(OpenApiErrorCode.SYMBOL_NOT_FOUND);
        }
    }

    private static long parseGrouping(String grouping) {
        try {
            long scaled = Money.toWire(grouping);
            if (scaled <= 0) {
                throw new IllegalArgumentException("grouping must be positive");
            }
            return scaled;
        } catch (ArithmeticException | IllegalArgumentException e) {
            throw new ApiException(OpenApiErrorCode.INVALID_DEPTH_GROUPING);
        }
    }

    private static CandleInterval parseInterval(String interval) {
        try {
            return CandleInterval.ofCode(interval);
        } catch (IllegalArgumentException e) {
            throw new ApiException(OpenApiErrorCode.INVALID_CANDLE_INTERVAL);
        }
    }
}
