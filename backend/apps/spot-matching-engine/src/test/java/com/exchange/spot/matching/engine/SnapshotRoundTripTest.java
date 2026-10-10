package com.exchange.spot.matching.engine;

import static com.exchange.contracts.spot.v1.OrderType.ORDER_TYPE_LIMIT;
import static com.exchange.contracts.spot.v1.OrderType.ORDER_TYPE_STOP_LOSS;
import static com.exchange.contracts.spot.v1.Side.SIDE_BUY;
import static com.exchange.contracts.spot.v1.Side.SIDE_SELL;
import static com.exchange.contracts.spot.v1.TimeInForce.TIME_IN_FORCE_GTC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.exchange.contracts.spot.v1.BookSnapshot;
import com.exchange.contracts.spot.v1.Command;
import com.exchange.contracts.spot.v1.Event;
import com.exchange.contracts.spot.v1.PlaceOrder;
import com.exchange.contracts.spot.v1.Side;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SnapshotRoundTripTest {

    private static final long ONE = 100_000_000L;
    private static final long P_63250 = 6_325_000_000_000L;
    private static final long P_63300 = 6_330_000_000_000L;
    private static final String SYMBOL = "BTCUSDT";

    private long clock = 1_700_000_000_000_000L;

    private Command place(UUID id, long userId, Side side, long price, long qty,
                          int makerBps, int takerBps) {
        return Command.newBuilder()
                .setCommandId(MatchingEngine.toBytes(UUID.randomUUID()))
                .setSymbol(SYMBOL).setTimestampUs(clock += 1_000)
                .setPlaceOrder(PlaceOrder.newBuilder()
                        .setOrderId(MatchingEngine.toBytes(id))
                        .setUserId(userId).setSide(side)
                        .setOrderType(ORDER_TYPE_LIMIT).setTimeInForce(TIME_IN_FORCE_GTC)
                        .setPrice(price).setQuantity(qty)
                        .setMakerFeeBps(makerBps).setTakerFeeBps(takerBps))
                .build();
    }

    private Command stop(UUID id, long userId, Side side, long stopPrice, long qty) {
        return Command.newBuilder()
                .setCommandId(MatchingEngine.toBytes(UUID.randomUUID()))
                .setSymbol(SYMBOL).setTimestampUs(clock += 1_000)
                .setPlaceOrder(PlaceOrder.newBuilder()
                        .setOrderId(MatchingEngine.toBytes(id))
                        .setUserId(userId).setSide(side)
                        .setOrderType(ORDER_TYPE_STOP_LOSS).setTimeInForce(TIME_IN_FORCE_GTC)
                        .setStopPrice(stopPrice).setQuantity(qty)
                        .setMakerFeeBps(-2).setTakerFeeBps(7))
                .build();
    }

    private Command trailingStop(UUID id, long userId, Side side, int trailingDeltaBps,
                                 long qty) {
        return Command.newBuilder()
                .setCommandId(MatchingEngine.toBytes(UUID.randomUUID()))
                .setSymbol(SYMBOL).setTimestampUs(clock += 1_000)
                .setPlaceOrder(PlaceOrder.newBuilder()
                        .setOrderId(MatchingEngine.toBytes(id))
                        .setUserId(userId).setSide(side)
                        .setOrderType(ORDER_TYPE_STOP_LOSS).setTimeInForce(TIME_IN_FORCE_GTC)
                        .setQuantity(qty).setTrailingDeltaBps(trailingDeltaBps)
                        .setMakerFeeBps(-2).setTakerFeeBps(7))
                .build();
    }

    @Test
    @DisplayName("a restored engine behaves identically to one that never restarted")
    void round_trip_preserves_behaviour() {
        MatchingEngine original = new MatchingEngine();
        List<Command> setup = List.of(
                place(UUID.randomUUID(), 1, SIDE_SELL, P_63250, 3 * ONE, -2, 7),
                place(UUID.randomUUID(), 2, SIDE_SELL, P_63300, 2 * ONE, -1, 8),
                place(UUID.randomUUID(), 3, SIDE_BUY, P_63250, ONE, -3, 6));  // partial fill
        setup.forEach(original::handle);
        original.handle(stop(UUID.randomUUID(), 4, SIDE_SELL, P_63250 - ONE, ONE));

        MatchingEngine restored = new MatchingEngine();
        restored.restore(original.snapshot(0, 42, clock));

        // The same next command must produce the same events from both.
        Command next = place(UUID.randomUUID(), 5, SIDE_BUY, P_63300, 4 * ONE, -1, 9);
        List<Event> fromOriginal = original.handle(next);
        List<Event> fromRestored = restored.handle(next);

        assertEquals(fromOriginal.size(), fromRestored.size());
        for (int i = 0; i < fromOriginal.size(); i++) {
            assertEquals(fromOriginal.get(i), fromRestored.get(i), "event " + i + " differs");
        }
    }

    @Test
    @DisplayName("a repeat that arrives after a restart is still a repeat")
    void a_repeated_command_is_still_caught_after_a_restore() {
        MatchingEngine original = new MatchingEngine();
        original.handle(place(UUID.randomUUID(), 1, SIDE_SELL, P_63250, ONE, -2, 7));
        Command takes = place(UUID.randomUUID(), 2, SIDE_BUY, P_63250, ONE, -2, 7);
        assertEquals(1, original.handle(takes).size());   // the trade; a filled taker never rests

        MatchingEngine restored = new MatchingEngine();
        restored.restore(original.snapshot(0, 10, clock));

        assertEquals(List.of(), restored.handle(takes),
                "a restored engine must remember what the original already did");
    }

    /** The snapshot is not the only thing that rebuilds the memory: replaying the commands
     *  after it does too, because replay runs the same handle(). So a command processed
     *  after the last snapshot, then lost with the process, is still known on restart. */
    @Test
    @DisplayName("a command replayed after the snapshot is remembered as well as one in it")
    void the_replay_rebuilds_the_memory_past_the_snapshot() {
        MatchingEngine original = new MatchingEngine();
        original.handle(place(UUID.randomUUID(), 1, SIDE_SELL, P_63250, 3 * ONE, -2, 7));
        BookSnapshot snapshot = original.snapshot(0, 1, clock);   // before the command below

        Command afterSnapshot = place(UUID.randomUUID(), 2, SIDE_BUY, P_63250, ONE, -2, 7);
        original.handle(afterSnapshot);

        // A restart: restore the snapshot, replay what came after it, then go live.
        MatchingEngine restarted = new MatchingEngine();
        restarted.restore(snapshot);
        restarted.handle(afterSnapshot);   // the replay

        assertEquals(List.of(), restarted.handle(afterSnapshot),
                "the replay put the id back; the repeat must be skipped");
    }

    @Test
    @DisplayName("time priority survives the round trip")
    void queue_position_is_preserved() {
        MatchingEngine original = new MatchingEngine();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        original.handle(place(first, 1, SIDE_SELL, P_63250, ONE, -2, 7));
        original.handle(place(second, 2, SIDE_SELL, P_63250, ONE, -2, 7));   // same price

        MatchingEngine restored = new MatchingEngine();
        restored.restore(original.snapshot(0, 10, clock));

        List<Event> events = restored.handle(place(UUID.randomUUID(), 3, SIDE_BUY,
                P_63250, ONE, -2, 7));

        // The earlier of the two must still fill first.
        assertEquals(MatchingEngine.toBytes(first),
                events.get(0).getTradeExecuted().getMakerOrderId());
    }

    @Test
    @DisplayName("a partially filled maker keeps its remainder and its own fee rate")
    void partial_fills_and_rates_survive() {
        MatchingEngine original = new MatchingEngine();
        UUID maker = UUID.randomUUID();
        original.handle(place(maker, 1, SIDE_SELL, P_63250, 3 * ONE, -5, 7));
        original.handle(place(UUID.randomUUID(), 2, SIDE_BUY, P_63250, ONE, -2, 7));

        MatchingEngine restored = new MatchingEngine();
        restored.restore(original.snapshot(0, 10, clock));

        List<Event> events = restored.handle(place(UUID.randomUUID(), 3, SIDE_BUY,
                P_63250, 2 * ONE, -1, 9));

        var trade = events.get(0).getTradeExecuted();
        assertEquals(2 * ONE, trade.getQuantity(), "the remainder survived");
        assertEquals(-5, trade.getMakerFeeBps(), "the maker's own rate survived");
        assertEquals(9, trade.getTakerFeeBps());
    }

    @Test
    void the_sequence_continues_rather_than_restarting() {
        MatchingEngine original = new MatchingEngine();
        original.handle(place(UUID.randomUUID(), 1, SIDE_SELL, P_63250, ONE, -2, 7));
        original.handle(place(UUID.randomUUID(), 2, SIDE_SELL, P_63300, ONE, -2, 7));

        MatchingEngine restored = new MatchingEngine();
        restored.restore(original.snapshot(0, 10, clock));

        List<Event> events = restored.handle(place(UUID.randomUUID(), 3, SIDE_BUY,
                P_63250 - ONE, ONE, -2, 7));

        assertEquals(3, events.get(0).getSequence(), "resuming at 1 would look like a gap");
    }

    @Test
    void parked_conditional_orders_survive() {
        MatchingEngine original = new MatchingEngine();
        original.handle(place(UUID.randomUUID(), 1, SIDE_SELL, P_63250, ONE, -2, 7));
        original.handle(place(UUID.randomUUID(), 2, SIDE_BUY, P_63250, ONE, -2, 7));
        UUID stopId = UUID.randomUUID();
        original.handle(stop(stopId, 3, SIDE_SELL, P_63250 - ONE, ONE));

        BookSnapshot snapshot = original.snapshot(0, 10, clock);
        assertEquals(1, snapshot.getSymbols(0).getPendingCount());

        MatchingEngine restored = new MatchingEngine();
        restored.restore(snapshot);

        // Cancelling it proves it is really parked in the restored engine.
        Command cancel = Command.newBuilder()
                .setCommandId(MatchingEngine.toBytes(UUID.randomUUID()))
                .setSymbol(SYMBOL).setTimestampUs(clock += 1_000)
                .setCancelOrder(com.exchange.contracts.spot.v1.CancelOrder.newBuilder()
                        .setUserId(3).setOrderId(MatchingEngine.toBytes(stopId)))
                .build();
        assertEquals(1, restored.handle(cancel).size());
    }

    @Test
    @DisplayName("an active trailing order's extreme survives the round trip, not reset "
            + "to the restored last trade price")
    void trailing_extreme_survives_the_round_trip() {
        MatchingEngine original = new MatchingEngine();
        original.handle(place(UUID.randomUUID(), 1, SIDE_SELL, P_63250, ONE, -2, 7));
        // Activates immediately at 63250.
        original.handle(place(UUID.randomUUID(), 2, SIDE_BUY, P_63250, ONE, -2, 7));
        original.handle(trailingStop(UUID.randomUUID(), 3, SIDE_SELL, 100, ONE));   // 1%

        // Ratchets the extreme up to 63300.
        original.handle(place(UUID.randomUUID(), 4, SIDE_SELL, P_63300, ONE, -2, 7));
        original.handle(place(UUID.randomUUID(), 5, SIDE_BUY, P_63300, ONE, -2, 7));

        // Last trade price ends up well below the extreme -- the two must differ here,
        // or restoring the extreme correctly vs. buggily resetting it to last_trade_price
        // would look identical below.
        long p62800 = 6_280_000_000_000L;
        original.handle(place(UUID.randomUUID(), 6, SIDE_SELL, p62800, ONE, -2, 7));
        original.handle(place(UUID.randomUUID(), 7, SIDE_BUY, p62800, ONE, -2, 7));

        MatchingEngine restored = new MatchingEngine();
        restored.restore(original.snapshot(0, 42, clock));

        // Clears the 1% band off the real 63300 extreme, but NOT off a buggily-reset
        // 62800 extreme -- this price only fires the stop if restore kept the real one.
        long p62600 = 6_260_000_000_000L;
        restored.handle(place(UUID.randomUUID(), 8, SIDE_BUY, p62600, 2 * ONE, -2, 7));
        List<Event> events = restored.handle(
                place(UUID.randomUUID(), 9, SIDE_SELL, p62600, ONE, -2, 7));

        long trades = events.stream()
                .filter(e -> e.getPayloadCase() == Event.PayloadCase.TRADE_EXECUTED).count();
        assertEquals(2, trades, "the seller's own trade, then the trailing stop's");
    }

    @Test
    @DisplayName("a parked quote-quantity conditional order survives the round trip")
    void quote_quantity_conditional_order_survives_the_round_trip() {
        MatchingEngine original = new MatchingEngine();
        original.handle(place(UUID.randomUUID(), 1, SIDE_SELL, P_63250, ONE, -2, 7));
        original.handle(place(UUID.randomUUID(), 2, SIDE_BUY, P_63250, ONE, -2, 7));

        UUID stopId = UUID.randomUUID();
        original.handle(Command.newBuilder()
                .setCommandId(MatchingEngine.toBytes(UUID.randomUUID()))
                .setSymbol(SYMBOL).setTimestampUs(clock += 1_000)
                .setPlaceOrder(PlaceOrder.newBuilder()
                        .setOrderId(MatchingEngine.toBytes(stopId))
                        .setUserId(3).setSide(SIDE_BUY)
                        .setOrderType(ORDER_TYPE_STOP_LOSS).setTimeInForce(TIME_IN_FORCE_GTC)
                        .setStopPrice(P_63300).setQuoteOrderQty(P_63300)
                        .setMakerFeeBps(-2).setTakerFeeBps(7))
                .build());

        MatchingEngine restored = new MatchingEngine();
        restored.restore(original.snapshot(0, 42, clock));

        // Reaching P_63300 must fire the restored stop as a quote-quantity order --
        // if the flag had been lost on restore, quantity would come back as 0 (nothing
        // was ever put in the base-quantity field for this order) and firing it would
        // throw ("quantity must be positive") instead of matching.
        restored.handle(place(UUID.randomUUID(), 4, SIDE_SELL, P_63300, 2 * ONE, -2, 7));
        List<Event> events = restored.handle(
                place(UUID.randomUUID(), 5, SIDE_BUY, P_63300, ONE, -2, 7));

        long trades = events.stream()
                .filter(e -> e.getPayloadCase() == Event.PayloadCase.TRADE_EXECUTED).count();
        assertEquals(2, trades, "the buyer's own trade, then the restored stop's");
    }

    @Test
    @DisplayName("restoring a symbol that already has state is refused")
    void restoring_over_live_state_is_refused() {
        MatchingEngine engine = new MatchingEngine();
        engine.handle(place(UUID.randomUUID(), 1, SIDE_SELL, P_63250, ONE, -2, 7));

        BookSnapshot snapshot = engine.snapshot(0, 1, clock);

        // Merging would silently double every resting order on that symbol.
        assertThrows(IllegalStateException.class, () -> engine.restore(snapshot));
    }
}
