package com.exchange.marketdata.stream;

import com.exchange.marketdata.candle.Candle;
import com.exchange.marketdata.candle.CandleInterval;
import com.exchange.marketdata.candle.CandleService;
import com.exchange.marketdata.candle.Market;
import com.exchange.marketdata.stream.exception.InvalidIntervalException;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * {@code kline} -- the bar a chart is drawing, as it fills in.
 *
 * <p>Builds the frames; {@link KlinePublisher} decides when to push them. Same split as
 * {@code DepthChannel}, and for the same structural reason: the handler collects every
 * {@link WsChannel} at construction, so a channel holding a reference back to it would be a
 * constructor cycle Spring refuses to start.
 *
 * <p><b>Nothing here aggregates.</b> The frames are the candles {@code market-data} already
 * wrote, read back and rendered. That matters because {@code MarketWsHandler} used to argue
 * against a kline channel on exactly that ground -- that it would mean a second
 * implementation of bucketing, and two implementations of the same arithmetic eventually
 * disagree. They would; this one simply does not do the arithmetic.
 *
 * <p>The trade-off, stated plainly: these frames are further behind than {@code trade} ones,
 * because a candle goes through Kafka, a batched Mongo write and a poll before it arrives,
 * rather than straight from the listener thread to the socket. A bar that settles a few
 * hundred milliseconds late is fine; a last price that does is not, which is why a client
 * showing a live price keeps the {@code trade} channel too.
 */
@Component
public class KlineChannel implements WsChannel {

    public static final String NAME = "kline";

    /**
     * How many recent buckets each poll examines.
     *
     * <p>Two, not one, and the second is not redundant. A bucket stops receiving updates
     * the moment the next one opens, so if a bucket's final trade lands between two polls
     * and the next bucket has already been created by then, looking only at the newest
     * would never send that bucket's last state -- leaving a bar permanently short of its
     * closing trades on every client watching. Reading one extra costs nothing: it is the
     * same query and the same document read.
     */
    private static final int RECENT_BUCKETS = 2;

    private final CandleService candles;
    private final Clock clock;

    // @Autowired is required here for the same reason TickerService's own comment gives:
    // this class has TWO constructors, and with more than one Spring cannot guess -- it
    // looks for a no-arg constructor instead and fails at startup with "No default
    // constructor found". Which is exactly what happened, and only an actually-booting
    // test could have said so.
    @org.springframework.beans.factory.annotation.Autowired
    public KlineChannel(CandleService candles) {
        this(candles, Clock.systemUTC());
    }

    /** Test seam -- a fixed clock makes {@code closed} deterministic. */
    KlineChannel(CandleService candles, Clock clock) {
        this.candles = candles;
        this.clock = clock;
    }

    @Override
    public String name() {
        return NAME;
    }

    /**
     * A kline subscription without a valid interval is meaningless -- there is no default
     * bar size, and guessing one would silently draw the wrong chart.
     *
     * @throws InvalidIntervalException deliberately distinct from an unknown channel: the client
     *         asked for something that exists, with an argument that does not.
     */
    @Override
    public void validate(Subscription subscription) {
        parse(subscription.interval());
    }

    /** The bar currently being drawn, so a chart has something live the moment it
     *  subscribes instead of waiting for the next trade. Absent when the market has never
     *  traded, which is the one case where there is genuinely no bar to send. */
    @Override
    public Optional<WsMessage<?>> initialFrame(Subscription subscription) {
        return recent(subscription).stream()
                .reduce((first, second) -> second)   // the newest of the two
                .map(this::frameOf);
    }

    /** The most recent buckets for this subscription, oldest first. */
    List<Candle> recent(Subscription subscription) {
        return candles.latest(Market.SPOT, subscription.symbol(),
                parse(subscription.interval()), RECENT_BUCKETS);
    }

    WsMessage<KlineData> frameOf(Candle candle) {
        return WsMessage.push(NAME, KlineData.of(candle, clock.instant()));
    }

    /** {@code CandleInterval} lives in a shared module and throws a plain
     *  {@code IllegalArgumentException} with no code attached, per AGENTS.md.
     *  Translating that into this app's own contract is this app's job. */
    private static CandleInterval parse(String interval) {
        try {
            return CandleInterval.ofCode(interval);
        } catch (IllegalArgumentException e) {
            throw new InvalidIntervalException(interval);
        }
    }
}
