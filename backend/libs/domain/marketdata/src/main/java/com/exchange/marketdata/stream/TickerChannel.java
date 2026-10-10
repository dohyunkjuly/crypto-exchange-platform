package com.exchange.marketdata.stream;

import com.exchange.marketdata.ticker.Ticker;
import com.exchange.marketdata.ticker.TickerSnapshot;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * {@code ticker} -- a market's rolling 24-hour summary, kept current.
 *
 * <p><b>Why this cannot be left to the client.</b> A page holding the {@code trade} channel
 * can follow the last price perfectly well, and nothing else. A 24h window slides at both
 * ends: trades arrive at the front and age out at the back, so a client can add an arriving
 * trade to the volume but has no way to subtract one leaving, can raise the high but not
 * lower it when the old high expires, and cannot move the opening price the change is
 * measured against. Without this channel a trading screen's header loads once and then
 * quietly drifts -- the price ticking while the volume and the day's range stay frozen at
 * whatever they were when the tab opened. Nothing looks broken, which is the problem.
 *
 * <p>Reads the app's {@link TickerSnapshot}, which recomputes every market on a timer for the REST
 * markets list, so this channel adds <b>no database work at all</b> -- only fan-out. That is
 * also why it does not reach for {@code TickerService} directly: recomputing per watched
 * symbol per tick would undo the caching the REST side already pays for.
 *
 * <p>Builds the frames; {@link TickerPublisher} decides when to push them. Same split as
 * depth and kline, and for the same structural reason: {@link MarketWsHandler} collects
 * every {@link WsChannel} at construction, so a channel holding a reference back to it would
 * be a constructor cycle Spring refuses to start.
 */
@Component
public class TickerChannel implements WsChannel {

    public static final String NAME = "ticker";

    private final TickerSnapshot tickers;

    public TickerChannel(TickerSnapshot tickers) {
        this.tickers = tickers;
    }

    @Override
    public String name() {
        return NAME;
    }

    /** A summary is state, so a subscriber gets it immediately rather than waiting for the
     *  next trade -- on a quiet market that could be minutes of an empty header. Absent only
     *  when the market has never traded, which is the one case with nothing to send. */
    @Override
    public Optional<WsMessage<?>> initialFrame(Subscription subscription) {
        return current(subscription.symbol()).map(TickerChannel::frameOf);
    }

    Optional<Ticker> current(String symbol) {
        return tickers.of(symbol);
    }

    static WsMessage<TickerData> frameOf(Ticker ticker) {
        return WsMessage.push(NAME, TickerData.of(ticker));
    }
}
