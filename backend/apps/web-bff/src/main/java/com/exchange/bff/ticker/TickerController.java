package com.exchange.bff.ticker;

import com.exchange.bff.common.errors.BffHttpErrorCode;
import com.exchange.bff.ticker.dto.TickerView;
import com.exchange.core.api.exception.ApiException;
import com.exchange.marketdata.candle.Market;
import com.exchange.marketdata.ticker.TickerService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A market's rolling 24-hour summary: last price, change, high, low, volume.
 *
 * <p>Its own package rather than {@code market}'s, because the WebSocket needs the same
 * numbers -- a trading screen's header cannot be kept current from the {@code trade} channel
 * alone, since a 24h window slides at both ends and a client can only ever add to it. So the
 * summary is read by two features, and by the rule in docs/STYLE.md, what both
 * need becomes a feature of its own rather than a folder inside one of them.
 *
 * <p><b>The paths stay under {@code /bff/market}</b>, unlike {@code /bff/symbols}. A ticker
 * genuinely is market data -- derived from the same candles, changing as constantly -- and
 * moving the URL would say otherwise for the sake of matching a package name. Packages group
 * by what owns the data; paths group by what a client is looking at, and the two do not have
 * to agree.
 */
@RestController
@RequestMapping("/bff/market")
public class TickerController {

    private final TickerService tickers;
    private final TickerCache allTickers;

    public TickerController(TickerService tickers, TickerCache allTickers) {
        this.tickers = tickers;
        this.allTickers = allTickers;
    }

    /**
     * Every market's summary in one call -- what the markets list panel polls.
     *
     * <p>Busiest first by quote volume. One request for all of them rather than one per
     * symbol: a list of fifty markets must not be fifty round trips, and the answer is the
     * same for every viewer, so it is computed once and cached ({@link TickerCache}) rather
     * than recomputed per request.
     *
     * <p>Filtering and re-sorting are the browser's job. The whole list is a few kilobytes,
     * and server-side filtering would mean a cache per filter for no benefit.
     */
    @GetMapping("/tickers")
    public List<TickerView> tickers() {
        return allTickers.all().stream().map(TickerView::of).toList();
    }

    /**
     * One market's summary, computed on demand rather than served from the cache -- a single
     * symbol is one query, and a caller asking for exactly one wants it current rather than
     * up to a refresh interval old.
     *
     * @throws ApiException {@code NO_MARKET_DATA} if this symbol has never traded. That is
     *         deliberately not {@code SYMBOL_NOT_FOUND}: a newly listed market exists and is
     *         perfectly valid, it simply has no history to summarise yet, and telling a
     *         client it does not exist would send them looking for the wrong bug.
     */
    @GetMapping("/{symbol}/ticker")
    public TickerView ticker(@PathVariable String symbol) {
        return tickers.of(Market.SPOT, symbol)
                .map(TickerView::of)
                .orElseThrow(() -> new ApiException(BffHttpErrorCode.NO_MARKET_DATA));
    }
}
