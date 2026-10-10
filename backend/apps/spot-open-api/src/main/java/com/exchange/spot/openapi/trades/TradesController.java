package com.exchange.spot.openapi.trades;

import com.exchange.spot.history.TimeRange;
import com.exchange.spot.openapi.common.ratelimit.Weight;
import com.exchange.spot.openapi.common.security.CurrentUser;
import com.exchange.spot.openapi.trades.dto.MyTradeView;
import com.exchange.spot.trade.TradeQueryService;
import com.exchange.spot.trade.dto.UserTrade;
import com.exchange.user.apikey.UserApiKey;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /v1/trades}: this account's fills, newest first, 90 days at a time. */
@RestController
@RequestMapping("/v1/trades")
public class TradesController {

    private static final int DEFAULT_PAGE_SIZE = 100;

    private final TradeQueryService trades;
    private final Clock clock;

    public TradesController(TradeQueryService trades, Clock clock) {
        this.trades = trades;
        this.clock = clock;
    }

    @GetMapping
    @Weight(10)
    public List<MyTradeView> myTrades(@CurrentUser UserApiKey key,
                                      @RequestParam(required = false) String symbol,
                                      @RequestParam(required = false) UserTrade.Side side,
                                      @RequestParam(required = false) Instant startTime,
                                      @RequestParam(required = false) Instant endTime,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {
        TimeRange range = TimeRange.of(startTime, endTime, clock.instant());
        int n = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, TradeQueryService.MAX_PAGE_SIZE);
        return trades.history(key.getUserId(), symbol, side, range, Math.max(page, 0), n)
                .stream().map(MyTradeView::of).toList();
    }
}
