package com.exchange.bff.trade;

import com.exchange.bff.common.security.TradingAccount;
import com.exchange.bff.trade.dto.MyTradeView;
import com.exchange.spot.trade.TradeQueryService;
import com.exchange.user.identity.User;
import com.exchange.spot.history.TimeRange;
import com.exchange.spot.trade.dto.UserTrade;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in user's own fills.
 *
 * <p><b>Not {@code /bff/market/{symbol}/trades}, which is the public tape.</b> The two look
 * alike and are opposites: that one is every trade in a market with nobody identified, this
 * one is only this user's trades with their own fee attached. Naming them both "trades" is
 * survivable only because the paths make the audience obvious -- everything under
 * {@code /bff/market} is public, everything here needs a session.
 *
 * <p><b>Requires a session, and is not gated by trading eligibility.</b> Reading what you
 * already did is not trading: a user whose KYC lapsed must still be able to see what they
 * paid, for the same reason they can still cancel and still read their orders. See
 * {@code @RequiresTrading} for why that gate is opt-in rather than a path pattern.
 */
@RestController
@RequestMapping("/bff/trades")
public class TradeController {

    /** A page a trade-history panel fills without scrolling. */
    private static final int DEFAULT_PAGE_SIZE = 50;

    private final TradeQueryService trades;

    public TradeController(TradeQueryService trades) {
        this.trades = trades;
    }

    /**
     * This account's fills, newest first.
     *
     * <p>{@code symbol} is optional: absent gives the account-wide history, present gives
     * one market's panel on a trading screen. Paged rather than capped, because a fill
     * history is the one list here that genuinely grows without limit -- an order can fill
     * a hundred times, and a user is entitled to all of it -- 90 days at a time, the
     * window defaulting to the last week.
     */
    @GetMapping
    public List<MyTradeView> myTrades(@TradingAccount User user,
                                      @RequestParam(required = false) String symbol,
                                      @RequestParam(required = false) UserTrade.Side side,
                                      @RequestParam(required = false) Instant from,
                                      @RequestParam(required = false) Instant to,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE)
                                      int size) {
        TimeRange range = TimeRange.of(from, to, Instant.now());
        return trades.history(user.getId(), symbol, side, range, page, clamp(size)).stream()
                .map(MyTradeView::of)
                .toList();
    }

    /** Clamped rather than rejected: a size is a request for how much, not an assertion, so
     *  the sane answer to an absurd one is the most we will give. */
    private static int clamp(int size) {
        return size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, TradeQueryService.MAX_PAGE_SIZE);
    }
}
