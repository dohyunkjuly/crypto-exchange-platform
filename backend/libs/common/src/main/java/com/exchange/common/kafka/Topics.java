package com.exchange.common.kafka;

/**
 * Kafka topic names. A market's messages share a partition and stay ordered — price-time
 * priority depends on that ordering being answerable. Commands go to the market's configured
 * partition ({@code spot_symbols.command_partition}); events are keyed by symbol.
 *
 * <p>Futures will get its own pair rather than sharing these: different engine, different
 * message shapes, and events spot has no concept of (liquidations, funding, ADL).
 */
public final class Topics {

    /** API to engine. Requests, which may be refused. */
    public static final String SPOT_COMMANDS = "exchange.spot.commands.v1";

    /** Engine to everyone. Facts, which cannot be. Its only output — depth, trades and
     *  candles are all projections built downstream from this. */
    public static final String SPOT_EVENTS = "exchange.spot.events.v1";

    /**
     * Settled state for pushing at one user's screen. Keyed by user id, not symbol, and the
     * only topic here allowed to drop messages (AGENTS.md) — everything on it is already in
     * MySQL and served over REST. See settlements.proto.
     */
    public static final String SPOT_SETTLEMENTS = "exchange.spot.settlements.v1";

    /**
     * Each price level's new size after market-data folds an event, for spot-open-api's
     * full-ladder feed. Keyed by symbol. Allowed to drop messages like settlements: the book
     * is in MongoDB and served over REST, and a client resyncs on a sequence gap. See
     * depth.proto.
     */
    public static final String SPOT_DEPTH = "exchange.spot.depth.v1";

    /**
     * The engine saying "partition N is live", once a second per partition it owns and has
     * finished replaying. The API refuses a new order on a partition it has not heard from
     * recently, instead of queueing it against a book nobody is running
     * (docs/reference/trading-gate.md). A minute of retention: the only question asked of it
     * is "what did the engine say in the last few seconds".
     */
    public static final String SPOT_ENGINE_STATUS = "exchange.spot.engine-status.v1";

    private Topics() {
    }
}
