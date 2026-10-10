package com.exchange.spot.matching.engine;

import static com.exchange.contracts.spot.v1.OrderType.ORDER_TYPE_LIMIT;
import static com.exchange.contracts.spot.v1.OrderType.ORDER_TYPE_MARKET;
import static com.exchange.contracts.spot.v1.OrderType.ORDER_TYPE_STOP_LOSS;
import static com.exchange.contracts.spot.v1.Side.SIDE_BUY;
import static com.exchange.contracts.spot.v1.Side.SIDE_SELL;
import static com.exchange.contracts.spot.v1.TimeInForce.TIME_IN_FORCE_FOK;
import static com.exchange.contracts.spot.v1.TimeInForce.TIME_IN_FORCE_GTC;
import static com.exchange.contracts.spot.v1.TimeInForce.TIME_IN_FORCE_IOC;
import static com.exchange.contracts.spot.v1.TimeInForce.TIME_IN_FORCE_UNSPECIFIED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.exchange.contracts.spot.v1.CancelAllOrders;
import com.exchange.contracts.spot.v1.CancelScope;
import com.exchange.contracts.spot.v1.CancelOrder;
import com.exchange.contracts.spot.v1.AmendOrder;
import com.exchange.contracts.spot.v1.AmendRejectReason;
import com.exchange.contracts.spot.v1.CancelReason;
import com.exchange.contracts.spot.v1.Command;
import com.exchange.contracts.spot.v1.Event;
import com.exchange.contracts.spot.v1.OrderType;
import com.exchange.contracts.spot.v1.PlaceOrder;
import com.exchange.contracts.spot.v1.RejectReason;
import com.exchange.contracts.spot.v1.SelfTradePreventionMode;
import com.exchange.contracts.spot.v1.Side;
import com.exchange.contracts.spot.v1.TimeInForce;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MatchingEngineTest {

    private static final long ONE = 100_000_000L;
    private static final long P_62000 = 6_200_000_000_000L;
    private static final long P_63250 = 6_325_000_000_000L;
    private static final long P_63300 = 6_330_000_000_000L;
    private static final String SYMBOL = "BTCUSDT";

    private final MatchingEngine engine = new MatchingEngine();
    private long clock = 1_700_000_000_000_000L;   // fixed; the engine never reads a real one

    private Command place(UUID orderId, long userId, Side side, long price, long qty) {
        return place(orderId, userId, side, ORDER_TYPE_LIMIT, TIME_IN_FORCE_GTC, price, qty);
    }

    private Command place(UUID orderId, long userId, Side side, OrderType type,
                          TimeInForce tif, long price, long qty) {
        return place(orderId, userId, side, type, tif, price, qty,
                SelfTradePreventionMode.SELF_TRADE_PREVENTION_MODE_UNSPECIFIED);
    }

    private Command place(UUID orderId, long userId, Side side, OrderType type,
                          TimeInForce tif, long price, long qty,
                          SelfTradePreventionMode stp) {
        PlaceOrder.Builder order = PlaceOrder.newBuilder()
                .setOrderId(MatchingEngine.toBytes(orderId))
                .setUserId(userId)
                .setSide(side)
                .setOrderType(type)
                .setTimeInForce(tif)
                .setQuantity(qty)
                .setMakerFeeBps(-2)
                .setTakerFeeBps(7)
                .setSelfTradePreventionMode(stp);
        if (price > 0) {
            order.setPrice(price);
        }
        return Command.newBuilder()
                .setCommandId(MatchingEngine.toBytes(UUID.randomUUID()))
                .setSymbol(SYMBOL)
                .setTimestampUs(clock += 1_000)
                .setPlaceOrder(order)
                .build();
    }

    private Command placeMarketQuoteQty(UUID orderId, long userId, Side side, long quoteOrderQty) {
        PlaceOrder.Builder order = PlaceOrder.newBuilder()
                .setOrderId(MatchingEngine.toBytes(orderId))
                .setUserId(userId)
                .setSide(side)
                .setOrderType(ORDER_TYPE_MARKET)
                .setQuoteOrderQty(quoteOrderQty)
                .setMakerFeeBps(-2)
                .setTakerFeeBps(7)
                .setSelfTradePreventionMode(SelfTradePreventionMode.SELF_TRADE_PREVENTION_MODE_UNSPECIFIED);
        return Command.newBuilder()
                .setCommandId(MatchingEngine.toBytes(UUID.randomUUID()))
                .setSymbol(SYMBOL)
                .setTimestampUs(clock += 1_000)
                .setPlaceOrder(order)
                .build();
    }

    private Command cancel(UUID orderId, long userId) {
        return Command.newBuilder()
                .setCommandId(MatchingEngine.toBytes(UUID.randomUUID()))
                .setSymbol(SYMBOL)
                .setTimestampUs(clock += 1_000)
                .setCancelOrder(CancelOrder.newBuilder()
                        .setUserId(userId)
                        .setOrderId(MatchingEngine.toBytes(orderId)))
                .build();
    }

    // ------------------------------------------------------------------ tests

    @Test
    void a_resting_order_produces_one_accepted_event() {
        List<Event> events = engine.handle(place(UUID.randomUUID(), 1, SIDE_BUY, P_63250, ONE));

        assertEquals(1, events.size());
        Event e = events.get(0);
        assertEquals(Event.PayloadCase.ORDER_ACCEPTED, e.getPayloadCase());
        assertEquals(SYMBOL, e.getSymbol());
        assertEquals(1, e.getSequence());
        assertEquals(ONE, e.getOrderAccepted().getRestingQuantity());
        org.junit.jupiter.api.Assertions.assertFalse(e.getOrderAccepted().getPendingTrigger());
    }

    @Test
    @DisplayName("each side is charged its OWN stamped rate, not the taker's")
    void maker_and_taker_rates_come_from_their_own_orders() {
        // The maker rests on a rebate tier; the taker crosses on a worse one.
        engine.handle(placeWithFees(UUID.randomUUID(), 1, SIDE_SELL, P_63250, ONE, -5, 3));
        List<Event> events = engine.handle(
                placeWithFees(UUID.randomUUID(), 2, SIDE_BUY, P_63250, ONE, -1, 9));

        var trade = events.get(0).getTradeExecuted();
        assertEquals(9, trade.getTakerFeeBps(), "the taker's own taker rate");
        assertEquals(-5, trade.getMakerFeeBps(), "the MAKER's own maker rate");
    }

    private Command placeWithFees(UUID orderId, long userId, Side side, long price,
                                  long qty, int makerBps, int takerBps) {
        return Command.newBuilder()
                .setCommandId(MatchingEngine.toBytes(UUID.randomUUID()))
                .setSymbol(SYMBOL)
                .setTimestampUs(clock += 1_000)
                .setPlaceOrder(PlaceOrder.newBuilder()
                        .setOrderId(MatchingEngine.toBytes(orderId))
                        .setUserId(userId).setSide(side)
                        .setOrderType(ORDER_TYPE_LIMIT).setTimeInForce(TIME_IN_FORCE_GTC)
                        .setPrice(price).setQuantity(qty)
                        .setMakerFeeBps(makerBps).setTakerFeeBps(takerBps))
                .build();
    }

    @Test
    @DisplayName("a fill produces TradeExecuted at the maker's price")
    void a_crossing_order_produces_a_trade() {
        engine.handle(place(UUID.randomUUID(), 1, SIDE_SELL, P_63250, ONE));
        List<Event> events = engine.handle(place(UUID.randomUUID(), 2, SIDE_BUY, P_63300, ONE));

        assertEquals(1, events.size());
        var trade = events.get(0).getTradeExecuted();
        assertEquals(P_63250, trade.getPrice());               // maker's price
        assertEquals(SIDE_BUY, trade.getTakerSide());
        assertEquals(7, trade.getTakerFeeBps());               // stamped, not looked up
        assertEquals(-2, trade.getMakerFeeBps());
        assertEquals(0, trade.getTakerRemainingQuantity());
    }

    @Test
    void sequence_increases_monotonically_across_every_event() {
        List<Event> all = new ArrayList<>();
        all.addAll(engine.handle(place(UUID.randomUUID(), 1, SIDE_SELL, P_63250, ONE)));
        all.addAll(engine.handle(place(UUID.randomUUID(), 2, SIDE_SELL, P_63300, ONE)));
        all.addAll(engine.handle(place(UUID.randomUUID(), 3, SIDE_BUY, P_63300, 3 * ONE)));

        for (int i = 0; i < all.size(); i++) {
            assertEquals(i + 1, all.get(i).getSequence());
        }
    }

    @Test
    void each_symbol_has_its_own_sequence() {
        engine.handle(place(UUID.randomUUID(), 1, SIDE_BUY, P_63250, ONE));

        Command other = Command.newBuilder()
                .setCommandId(MatchingEngine.toBytes(UUID.randomUUID()))
                .setSymbol("ETHUSDT")
                .setTimestampUs(clock += 1_000)
                .setPlaceOrder(PlaceOrder.newBuilder()
                        .setOrderId(MatchingEngine.toBytes(UUID.randomUUID()))
                        .setUserId(1).setSide(SIDE_BUY).setOrderType(ORDER_TYPE_LIMIT)
                        .setTimeInForce(TIME_IN_FORCE_GTC).setPrice(P_63250).setQuantity(ONE))
                .build();

        List<Event> events = engine.handle(other);
        assertEquals(1, events.get(0).getSequence());   // ETHUSDT starts at 1, not 2
        assertEquals(2, engine.symbolCount());
    }

    @Test
    @DisplayName("an IOC remainder is cancelled, not rested")
    void ioc_remainder_emits_a_cancellation() {
        engine.handle(place(UUID.randomUUID(), 1, SIDE_SELL, P_63250, ONE));
        List<Event> events = engine.handle(place(UUID.randomUUID(), 2, SIDE_BUY,
                ORDER_TYPE_LIMIT, TIME_IN_FORCE_IOC, P_63250, 3 * ONE));

        assertEquals(2, events.size());
        assertEquals(Event.PayloadCase.TRADE_EXECUTED, events.get(0).getPayloadCase());
        var canceled = events.get(1).getOrderCanceled();
        assertEquals(CancelReason.CANCEL_REASON_IOC_REMAINDER, canceled.getReason());
        assertEquals(2 * ONE, canceled.getCanceledQuantity());
    }

    @Test
    @DisplayName("an unset mode never self-trades: the resting order is expired instead")
    void unset_mode_expires_the_maker_instead_of_self_trading() {
        UUID own = UUID.randomUUID();
        engine.handle(place(own, 1, SIDE_SELL, P_63250, ONE));

        List<Event> events = engine.handle(place(UUID.randomUUID(), 1, SIDE_BUY, P_63250, ONE));

        assertTrue(events.stream().noneMatch(
                e -> e.getPayloadCase() == Event.PayloadCase.TRADE_EXECUTED));
        assertTrue(events.stream().anyMatch(
                e -> e.getPayloadCase() == Event.PayloadCase.ORDER_CANCELED
                        && e.getOrderCanceled().getReason()
                                == CancelReason.CANCEL_REASON_SELF_TRADE_PREVENTION));
    }

    @Test
    @DisplayName("FOK with EXPIRE_BOTH is rejected before it can touch the book")
    void fok_with_expire_both_is_rejected() {
        engine.handle(place(UUID.randomUUID(), 1, SIDE_SELL, P_63250, ONE));

        List<Event> events = engine.handle(place(UUID.randomUUID(), 1, SIDE_BUY,
                ORDER_TYPE_LIMIT, TIME_IN_FORCE_FOK, P_63250, ONE,
                SelfTradePreventionMode.SELF_TRADE_PREVENTION_MODE_EXPIRE_BOTH));

        assertEquals(1, events.size());
        assertEquals(Event.PayloadCase.ORDER_REJECTED, events.get(0).getPayloadCase());
        assertEquals(RejectReason.REJECT_REASON_UNSPECIFIED,
                events.get(0).getOrderRejected().getReason());
    }

    @Test
    @DisplayName("EXPIRE_MAKER emits a cancellation before the fill it enabled")
    void stp_emits_a_cancellation_with_everything_a_consumer_needs() {
        UUID own = UUID.randomUUID();
        engine.handle(place(own, 1, SIDE_SELL, P_63250, ONE));
        engine.handle(place(UUID.randomUUID(), 2, SIDE_SELL, P_63300, ONE));

        List<Event> events = engine.handle(place(UUID.randomUUID(), 1, SIDE_BUY,
                ORDER_TYPE_LIMIT, TIME_IN_FORCE_GTC, P_63300, ONE,
                SelfTradePreventionMode.SELF_TRADE_PREVENTION_MODE_EXPIRE_MAKER));

        assertEquals(2, events.size());
        var canceled = events.get(0).getOrderCanceled();
        assertEquals(CancelReason.CANCEL_REASON_SELF_TRADE_PREVENTION, canceled.getReason());
        assertEquals(P_63250, canceled.getPrice());          // which level to subtract from
        assertEquals(SIDE_SELL, canceled.getSide());
        assertEquals(ONE, canceled.getCanceledQuantity());

        assertEquals(Event.PayloadCase.TRADE_EXECUTED, events.get(1).getPayloadCase());
    }

    @Test
    @DisplayName("EXPIRE_TAKER cancels the taker's own remainder, with the right reason code")
    void expire_taker_emits_a_cancellation_for_the_taker_itself() {
        UUID own = UUID.randomUUID();
        engine.handle(place(own, 1, SIDE_SELL, P_63250, ONE));

        List<Event> events = engine.handle(place(UUID.randomUUID(), 1, SIDE_BUY,
                ORDER_TYPE_LIMIT, TIME_IN_FORCE_GTC, P_63250, ONE,
                SelfTradePreventionMode.SELF_TRADE_PREVENTION_MODE_EXPIRE_TAKER));

        assertEquals(1, events.size());
        var canceled = events.get(0).getOrderCanceled();
        assertEquals(CancelReason.CANCEL_REASON_SELF_TRADE_PREVENTION, canceled.getReason());
        assertEquals(SIDE_BUY, canceled.getSide());
        assertEquals(ONE, canceled.getCanceledQuantity());
    }

    @Test
    void cancelling_emits_one_event() {
        UUID id = UUID.randomUUID();
        engine.handle(place(id, 1, SIDE_BUY, P_63250, ONE));

        List<Event> events = engine.handle(cancel(id, 1));

        assertEquals(1, events.size());
        assertEquals(CancelReason.CANCEL_REASON_USER_REQUESTED,
                events.get(0).getOrderCanceled().getReason());
    }

    @Test
    @DisplayName("cancelling an order that already filled is silence, not an error")
    void cancel_racing_a_fill_emits_nothing() {
        UUID id = UUID.randomUUID();
        engine.handle(place(id, 1, SIDE_SELL, P_63250, ONE));
        engine.handle(place(UUID.randomUUID(), 2, SIDE_BUY, P_63250, ONE));

        assertTrue(engine.handle(cancel(id, 1)).isEmpty());
    }

    /** A market order is the case the resting-order id guard cannot catch: it never
     *  rests, so nothing in the book remembers it. Only the command id does. */
    @Test
    @DisplayName("the same command a second time does nothing: no trades, no events, no sequence")
    void a_repeated_command_is_skipped() {
        engine.handle(place(UUID.randomUUID(), 1, SIDE_SELL, P_63250, 5 * ONE));
        Command marketBuy = place(UUID.randomUUID(), 2, SIDE_BUY, ORDER_TYPE_MARKET,
                TIME_IN_FORCE_IOC, 0, ONE);

        List<Event> first = engine.handle(marketBuy);
        long sequenceAfterFirst = first.get(first.size() - 1).getSequence();

        List<Event> second = engine.handle(marketBuy);

        assertTrue(second.isEmpty(), "the repeat produced events");
        // The maker still has 4.0 left: the repeat did not trade a second time.
        List<Event> probe = engine.handle(place(UUID.randomUUID(), 3, SIDE_BUY, P_63250, 4 * ONE));
        assertEquals(4 * ONE, probe.stream()
                .filter(e -> e.getPayloadCase() == Event.PayloadCase.TRADE_EXECUTED)
                .mapToLong(e -> e.getTradeExecuted().getQuantity()).sum());
        assertEquals(sequenceAfterFirst + 1, probe.get(0).getSequence(),
                "the repeat consumed no sequence numbers");
    }

    @Test
    void a_repeat_of_one_command_does_not_block_a_different_one_with_the_same_order() {
        UUID orderId = UUID.randomUUID();
        Command original = place(orderId, 1, SIDE_BUY, P_63250, ONE);
        engine.handle(original);
        engine.handle(original);   // the repeat, skipped

        // A genuinely new command about that order still goes through.
        assertEquals(1, engine.handle(cancel(orderId, 1)).size());
    }

    @Test
    void cancel_all_takes_only_that_users_orders() {
        engine.handle(place(UUID.randomUUID(), 1, SIDE_BUY, P_63250, ONE));
        engine.handle(place(UUID.randomUUID(), 1, SIDE_BUY, P_63250 - ONE, ONE));
        engine.handle(place(UUID.randomUUID(), 2, SIDE_BUY, P_63250 - ONE, ONE));

        Command cancelAll = Command.newBuilder()
                .setCommandId(MatchingEngine.toBytes(UUID.randomUUID()))
                .setSymbol(SYMBOL)
                .setTimestampUs(clock += 1_000)
                .setCancelAllOrders(CancelAllOrders.newBuilder().setUserId(1))
                .build();

        assertEquals(2, engine.handle(cancelAll).size());
    }

    /** The filters are the whole point of sending one command instead of a cancel each, so
     *  each one has to narrow what the engine touches -- not just what the API counted. */
    @Test
    void a_filtered_cancel_all_takes_only_what_matches() {
        seedOneOfEachForUserOne();
        assertEquals(1, engine.handle(cancelAll(1, SIDE_BUY, null,
                CancelScope.CANCEL_SCOPE_UNSPECIFIED)).size());

        // The sell and the parked stop are still there.
        assertEquals(2, engine.handle(cancelAll(1, null, null,
                CancelScope.CANCEL_SCOPE_UNSPECIFIED)).size());
    }

    @Test
    void a_cancel_all_scoped_to_the_book_leaves_the_parked_stop() {
        seedOneOfEachForUserOne();

        assertEquals(2, engine.handle(cancelAll(1, null, null,
                CancelScope.CANCEL_SCOPE_BOOK)).size());
        assertEquals(1, engine.handle(cancelAll(1, null, null,
                CancelScope.CANCEL_SCOPE_TRIGGERS)).size());
    }

    @Test
    void a_cancel_all_scoped_to_triggers_leaves_the_resting_orders() {
        seedOneOfEachForUserOne();

        assertEquals(1, engine.handle(cancelAll(1, null, null,
                CancelScope.CANCEL_SCOPE_TRIGGERS)).size());
        assertEquals(2, engine.handle(cancelAll(1, null, null,
                CancelScope.CANCEL_SCOPE_BOOK)).size());
    }

    /** User 1 left with a resting buy, a resting sell and a stop parked below the market. */
    private void seedOneOfEachForUserOne() {
        // A trade first: a conditional order needs a last price to be measured against.
        engine.handle(place(UUID.randomUUID(), 2, SIDE_SELL, P_63250, ONE));
        engine.handle(place(UUID.randomUUID(), 3, SIDE_BUY, P_63250, ONE));

        engine.handle(place(UUID.randomUUID(), 1, SIDE_BUY, P_63250 - ONE, ONE));
        engine.handle(place(UUID.randomUUID(), 1, SIDE_SELL, P_63300, ONE));
        engine.handle(stop(UUID.randomUUID(), 1, SIDE_SELL, P_63250 - 2 * ONE, ONE));
    }

    private Command cancelAll(long userId, Side side, OrderType type, CancelScope scope) {
        CancelAllOrders.Builder request = CancelAllOrders.newBuilder().setUserId(userId);
        if (side != null) {
            request.setSide(side);
        }
        if (type != null) {
            request.setOrderType(type);
        }
        return Command.newBuilder()
                .setCommandId(MatchingEngine.toBytes(UUID.randomUUID()))
                .setSymbol(SYMBOL)
                .setTimestampUs(clock += 1_000)
                .setCancelAllOrders(request.setScope(scope))
                .build();
    }

    @Test
    @DisplayName("a stop on a market that has never traded is refused, not parked")
    void a_conditional_order_needs_a_price_to_trigger_against() {
        List<Event> events = engine.handle(stop(UUID.randomUUID(), 1, SIDE_SELL,
                P_63250 - ONE, ONE));

        assertEquals(RejectReason.REJECT_REASON_WOULD_TRIGGER_IMMEDIATELY,
                events.get(0).getOrderRejected().getReason());
    }

    @Test
    @DisplayName("a stop already on the wrong side of the market is refused")
    void a_stop_that_would_fire_at_once_is_refused() {
        trade();   // establishes a last price of P_63250

        List<Event> events = engine.handle(stop(UUID.randomUUID(), 3, SIDE_SELL,
                P_63300, ONE));   // sell-stop ABOVE the market fires instantly

        assertEquals(RejectReason.REJECT_REASON_WOULD_TRIGGER_IMMEDIATELY,
                events.get(0).getOrderRejected().getReason());
    }

    @Test
    @DisplayName("a parked stop is accepted but rests nothing on the book")
    void a_valid_stop_is_parked() {
        trade();

        List<Event> events = engine.handle(stop(UUID.randomUUID(), 3, SIDE_SELL,
                P_63250 - ONE, ONE));

        assertEquals(1, events.size());
        assertEquals(Event.PayloadCase.ORDER_ACCEPTED, events.get(0).getPayloadCase());
        var accepted = events.get(0).getOrderAccepted();
        assertEquals(0, accepted.getRestingQuantity());
        // What it waits on travels with it, so a consumer can show it without a lookup.
        org.junit.jupiter.api.Assertions.assertTrue(accepted.getPendingTrigger());
        assertEquals(P_63250 - ONE, accepted.getStopPrice());
        org.junit.jupiter.api.Assertions.assertFalse(accepted.hasTrailingDeltaBps());
        org.junit.jupiter.api.Assertions.assertFalse(accepted.hasQuoteOrderQty());
    }

    @Test
    @DisplayName("a parked quote-sized stop carries its budget, and a trailing one its distance")
    void a_parked_order_carries_what_it_waits_on() {
        trade();

        var quote = engine.handle(stopQuoteQty(UUID.randomUUID(), 3, SIDE_BUY, P_63250 + ONE, 500 * ONE))
                .get(0).getOrderAccepted();
        org.junit.jupiter.api.Assertions.assertTrue(quote.getPendingTrigger());
        assertEquals(500 * ONE, quote.getQuoteOrderQty());

        var trailing = engine.handle(trailingStop(UUID.randomUUID(), 3, SIDE_SELL, 0, ONE, 150))
                .get(0).getOrderAccepted();
        org.junit.jupiter.api.Assertions.assertTrue(trailing.getPendingTrigger());
        assertEquals(150, trailing.getTrailingDeltaBps());
        // Tracking from acceptance: there is no fixed price to report.
        org.junit.jupiter.api.Assertions.assertFalse(trailing.hasStopPrice());
    }

    @Test
    @DisplayName("a stop fires when a fill reaches it, and matches in the same command")
    void a_stop_fires_on_a_fill_that_reaches_it() {
        trade();                                    // last price P_63250

        UUID stopId = UUID.randomUUID();
        engine.handle(stop(stopId, 3, SIDE_SELL, P_63250 - ONE, ONE));   // waiting below
        engine.handle(place(UUID.randomUUID(), 4, SIDE_BUY, P_63250 - 2 * ONE, 5 * ONE));

        // A sell at the bid drops the price to where the stop is waiting.
        List<Event> events = engine.handle(
                place(UUID.randomUUID(), 5, SIDE_SELL, P_63250 - 2 * ONE, ONE));

        // The seller's own trade, then the triggered stop's trade — one command.
        long trades = events.stream()
                .filter(e -> e.getPayloadCase() == Event.PayloadCase.TRADE_EXECUTED).count();
        assertEquals(2, trades);
    }

    @Test
    void cancelling_a_parked_stop_emits_a_cancellation() {
        trade();
        UUID stopId = UUID.randomUUID();
        engine.handle(stop(stopId, 3, SIDE_SELL, P_63250 - ONE, ONE));

        List<Event> events = engine.handle(cancel(stopId, 3));

        assertEquals(1, events.size());
        assertEquals(CancelReason.CANCEL_REASON_USER_REQUESTED,
                events.get(0).getOrderCanceled().getReason());
    }

    /** Two orders that cross, leaving a last trade price of P_63250. */
    private void trade() {
        engine.handle(place(UUID.randomUUID(), 1, SIDE_SELL, P_63250, ONE));
        engine.handle(place(UUID.randomUUID(), 2, SIDE_BUY, P_63250, ONE));
    }

    private Command stop(UUID orderId, long userId, Side side, long stopPrice, long qty) {
        return Command.newBuilder()
                .setCommandId(MatchingEngine.toBytes(UUID.randomUUID()))
                .setSymbol(SYMBOL)
                .setTimestampUs(clock += 1_000)
                .setPlaceOrder(PlaceOrder.newBuilder()
                        .setOrderId(MatchingEngine.toBytes(orderId))
                        .setUserId(userId)
                        .setSide(side)
                        .setOrderType(ORDER_TYPE_STOP_LOSS)
                        .setTimeInForce(TIME_IN_FORCE_GTC)
                        .setStopPrice(stopPrice)
                        .setQuantity(qty)
                        .setMakerFeeBps(-2)
                        .setTakerFeeBps(7))
                .build();
    }

    private Command stopQuoteQty(UUID orderId, long userId, Side side, long stopPrice,
                                 long quoteOrderQty) {
        return Command.newBuilder()
                .setCommandId(MatchingEngine.toBytes(UUID.randomUUID()))
                .setSymbol(SYMBOL)
                .setTimestampUs(clock += 1_000)
                .setPlaceOrder(PlaceOrder.newBuilder()
                        .setOrderId(MatchingEngine.toBytes(orderId))
                        .setUserId(userId)
                        .setSide(side)
                        .setOrderType(ORDER_TYPE_STOP_LOSS)
                        .setTimeInForce(TIME_IN_FORCE_GTC)
                        .setStopPrice(stopPrice)
                        .setQuoteOrderQty(quoteOrderQty)
                        .setMakerFeeBps(-2)
                        .setTakerFeeBps(7))
                .build();
    }

    @Test
    @DisplayName("a quote-quantity stop-loss fires as a quote-quantity market order")
    void quote_quantity_stop_loss_fires_correctly() {
        trade();   // last price P_63250

        // RISE (STOP_LOSS + BUY): budget = exactly $63,300, one unit's worth at 63300.
        engine.handle(stopQuoteQty(UUID.randomUUID(), 3, SIDE_BUY, P_63300, P_63300));

        engine.handle(place(UUID.randomUUID(), 4, SIDE_SELL, P_63300, 2 * ONE));
        List<Event> events = engine.handle(place(UUID.randomUUID(), 5, SIDE_BUY, P_63300, ONE));

        long trades = events.stream()
                .filter(e -> e.getPayloadCase() == Event.PayloadCase.TRADE_EXECUTED).count();
        assertEquals(2, trades, "the buyer's own trade, then the fired stop's");

        var stopTrade = events.stream()
                .filter(e -> e.getPayloadCase() == Event.PayloadCase.TRADE_EXECUTED)
                .map(Event::getTradeExecuted)
                .filter(t -> t.getTakerUserId() == 3)
                .findFirst().orElseThrow();
        assertEquals(ONE, stopTrade.getQuantity());
        assertEquals(0, stopTrade.getTakerRemainingQuantity(), "the whole budget was spent exactly");
    }

    @Test
    @DisplayName("a market-firing BUY without quote_order_qty is rejected")
    void market_firing_buy_without_quote_order_qty_is_rejected() {
        List<Event> events = engine.handle(
                place(UUID.randomUUID(), 1, SIDE_BUY, ORDER_TYPE_MARKET,
                        TimeInForce.TIME_IN_FORCE_UNSPECIFIED, 0, ONE));

        assertEquals(1, events.size());
        assertEquals(Event.PayloadCase.ORDER_REJECTED, events.get(0).getPayloadCase());
    }

    @Test
    @DisplayName("a market-firing SELL with quote_order_qty is rejected")
    void market_firing_sell_with_quote_order_qty_is_rejected() {
        Command command = Command.newBuilder()
                .setCommandId(MatchingEngine.toBytes(UUID.randomUUID()))
                .setSymbol(SYMBOL)
                .setTimestampUs(clock += 1_000)
                .setPlaceOrder(PlaceOrder.newBuilder()
                        .setOrderId(MatchingEngine.toBytes(UUID.randomUUID()))
                        .setUserId(1)
                        .setSide(SIDE_SELL)
                        .setOrderType(ORDER_TYPE_MARKET)
                        .setQuoteOrderQty(P_63250)
                        .setMakerFeeBps(-2)
                        .setTakerFeeBps(7))
                .build();

        List<Event> events = engine.handle(command);

        assertEquals(1, events.size());
        assertEquals(Event.PayloadCase.ORDER_REJECTED, events.get(0).getPayloadCase());
    }

    @Test
    @DisplayName("a BUY carrying both quantity and quote_order_qty is rejected")
    void buy_with_both_quantity_and_quote_order_qty_is_rejected() {
        Command command = Command.newBuilder()
                .setCommandId(MatchingEngine.toBytes(UUID.randomUUID()))
                .setSymbol(SYMBOL)
                .setTimestampUs(clock += 1_000)
                .setPlaceOrder(PlaceOrder.newBuilder()
                        .setOrderId(MatchingEngine.toBytes(UUID.randomUUID()))
                        .setUserId(1)
                        .setSide(SIDE_BUY)
                        .setOrderType(ORDER_TYPE_MARKET)
                        .setQuantity(ONE)
                        .setQuoteOrderQty(P_63250)
                        .setMakerFeeBps(-2)
                        .setTakerFeeBps(7))
                .build();

        List<Event> events = engine.handle(command);

        assertEquals(1, events.size());
        assertEquals(Event.PayloadCase.ORDER_REJECTED, events.get(0).getPayloadCase());
    }

    @Test
    @DisplayName("quote_order_qty is rejected on a *_LIMIT conditional order")
    void quote_order_qty_rejected_on_stop_loss_limit() {
        Command command = Command.newBuilder()
                .setCommandId(MatchingEngine.toBytes(UUID.randomUUID()))
                .setSymbol(SYMBOL)
                .setTimestampUs(clock += 1_000)
                .setPlaceOrder(PlaceOrder.newBuilder()
                        .setOrderId(MatchingEngine.toBytes(UUID.randomUUID()))
                        .setUserId(1)
                        .setSide(SIDE_BUY)
                        .setOrderType(OrderType.ORDER_TYPE_STOP_LOSS_LIMIT)
                        .setTimeInForce(TIME_IN_FORCE_GTC)
                        .setStopPrice(P_63300)
                        .setPrice(P_63300)
                        .setQuoteOrderQty(ONE))
                .build();

        List<Event> events = engine.handle(command);

        assertEquals(1, events.size());
        assertEquals(Event.PayloadCase.ORDER_REJECTED, events.get(0).getPayloadCase());
    }

    /** {@code stopPrice <= 0} means no activation price -- trailing starts immediately. */
    private Command trailingStop(UUID orderId, long userId, Side side, long stopPrice,
                                 long qty, int trailingDeltaBps) {
        PlaceOrder.Builder order = PlaceOrder.newBuilder()
                .setOrderId(MatchingEngine.toBytes(orderId))
                .setUserId(userId)
                .setSide(side)
                .setOrderType(ORDER_TYPE_STOP_LOSS)
                .setTimeInForce(TIME_IN_FORCE_GTC)
                .setQuantity(qty)
                .setMakerFeeBps(-2)
                .setTakerFeeBps(7)
                .setTrailingDeltaBps(trailingDeltaBps);
        if (stopPrice > 0) {
            order.setStopPrice(stopPrice);
        }
        return Command.newBuilder()
                .setCommandId(MatchingEngine.toBytes(UUID.randomUUID()))
                .setSymbol(SYMBOL)
                .setTimestampUs(clock += 1_000)
                .setPlaceOrder(order)
                .build();
    }

    @Test
    @DisplayName("trailing_delta_bps outside [10, 2000] is rejected")
    void trailing_delta_bps_out_of_bounds_is_rejected() {
        trade();

        List<Event> events = engine.handle(
                trailingStop(UUID.randomUUID(), 3, SIDE_SELL, 0, ONE, 1));

        assertEquals(1, events.size());
        assertEquals(Event.PayloadCase.ORDER_REJECTED, events.get(0).getPayloadCase());
        assertEquals(RejectReason.REJECT_REASON_UNSPECIFIED,
                events.get(0).getOrderRejected().getReason());
    }

    @Test
    @DisplayName("trailing_delta_bps on a non-conditional order is rejected")
    void trailing_delta_bps_on_non_conditional_is_rejected() {
        Command command = Command.newBuilder()
                .setCommandId(MatchingEngine.toBytes(UUID.randomUUID()))
                .setSymbol(SYMBOL)
                .setTimestampUs(clock += 1_000)
                .setPlaceOrder(PlaceOrder.newBuilder()
                        .setOrderId(MatchingEngine.toBytes(UUID.randomUUID()))
                        .setUserId(1)
                        .setSide(SIDE_BUY)
                        .setOrderType(ORDER_TYPE_LIMIT)
                        .setTimeInForce(TIME_IN_FORCE_GTC)
                        .setPrice(P_63250)
                        .setQuantity(ONE)
                        .setTrailingDeltaBps(100))
                .build();

        List<Event> events = engine.handle(command);

        assertEquals(1, events.size());
        assertEquals(Event.PayloadCase.ORDER_REJECTED, events.get(0).getPayloadCase());
    }

    @Test
    @DisplayName("a trailing stop ratchets with the market and fires on a reversal")
    void a_trailing_stop_ratchets_and_fires_on_reversal() {
        trade();   // last price P_63250 -- the trailing stop activates here, immediately

        engine.handle(trailingStop(UUID.randomUUID(), 3, SIDE_SELL, 0, ONE, 100));   // 1%

        // Push the price up -- ratchets the trailing stop's extreme, does not fire it.
        engine.handle(place(UUID.randomUUID(), 4, SIDE_SELL, P_63300, ONE));
        engine.handle(place(UUID.randomUUID(), 5, SIDE_BUY, P_63300, ONE));

        // A reversal of more than 1% off the new P_63300 peak -- extra size left resting
        // so the fired stop (a market order) has something to match against too.
        engine.handle(place(UUID.randomUUID(), 6, SIDE_BUY, P_62000, 2 * ONE));
        List<Event> events = engine.handle(place(UUID.randomUUID(), 7, SIDE_SELL, P_62000, ONE));

        // The seller's own trade, then the fired trailing stop's trade -- one command.
        long trades = events.stream()
                .filter(e -> e.getPayloadCase() == Event.PayloadCase.TRADE_EXECUTED).count();
        assertEquals(2, trades);
    }

    @Test
    void an_unset_enum_is_rejected_rather_than_defaulted() {
        Command bad = Command.newBuilder()
                .setCommandId(MatchingEngine.toBytes(UUID.randomUUID()))
                .setSymbol(SYMBOL)
                .setTimestampUs(clock += 1_000)
                .setPlaceOrder(PlaceOrder.newBuilder()
                        .setOrderId(MatchingEngine.toBytes(UUID.randomUUID()))
                        .setUserId(1)
                        .setQuantity(ONE))          // no side, type or TIF set
                .build();

        List<Event> events = engine.handle(bad);
        assertEquals(RejectReason.REJECT_REASON_UNSPECIFIED,
                events.get(0).getOrderRejected().getReason());
    }

    @Test
    void a_market_order_against_an_empty_book_cancels_entirely() {
        // SELL, not BUY: a bare-quantity BUY on MARKET is rejected (see the
        // quote_order_qty tests below) -- SELL is unaffected, since quantity is
        // always the right field for the asset it's giving up.
        List<Event> events = engine.handle(place(UUID.randomUUID(), 1, SIDE_SELL,
                ORDER_TYPE_MARKET, TIME_IN_FORCE_UNSPECIFIED, 0, ONE));

        assertEquals(1, events.size());
        assertEquals(ONE, events.get(0).getOrderCanceled().getCanceledQuantity());
    }

    @Test
    @DisplayName("a market order carrying an explicit time in force is refused")
    void a_market_order_with_a_time_in_force_is_rejected() {
        List<Event> events = engine.handle(place(UUID.randomUUID(), 1, SIDE_BUY,
                ORDER_TYPE_MARKET, TIME_IN_FORCE_IOC, 0, ONE));

        assertEquals(1, events.size());
        assertEquals(RejectReason.REJECT_REASON_UNSPECIFIED,
                events.get(0).getOrderRejected().getReason());
    }

    @Test
    @DisplayName("replay produces byte-identical events — no clock, no randomness")
    void replay_is_deterministic() {
        List<Command> script = List.of(
                place(UUID.randomUUID(), 1, SIDE_SELL, P_63250, ONE),
                place(UUID.randomUUID(), 2, SIDE_SELL, P_63300, 2 * ONE),
                place(UUID.randomUUID(), 3, SIDE_BUY, P_63300, 3 * ONE),
                place(UUID.randomUUID(), 1, SIDE_BUY, P_63250, ONE));

        List<Event> first = new ArrayList<>();
        List<Event> second = new ArrayList<>();
        MatchingEngine a = new MatchingEngine();
        MatchingEngine b = new MatchingEngine();
        script.forEach(c -> first.addAll(a.handle(c)));
        script.forEach(c -> second.addAll(b.handle(c)));

        assertEquals(first.size(), second.size());
        for (int i = 0; i < first.size(); i++) {
            assertEquals(first.get(i), second.get(i), "event " + i + " diverged on replay");
        }
    }

    @Test
    @DisplayName("trade ids differ between trades but are reproducible across runs")
    void trade_ids_are_deterministic_and_unique() {
        List<Command> script = List.of(
                place(UUID.randomUUID(), 1, SIDE_SELL, P_63250, ONE),
                place(UUID.randomUUID(), 2, SIDE_SELL, P_63300, ONE),
                place(UUID.randomUUID(), 3, SIDE_BUY, P_63300, 2 * ONE));

        MatchingEngine a = new MatchingEngine();
        MatchingEngine b = new MatchingEngine();
        List<Event> ea = new ArrayList<>();
        List<Event> eb = new ArrayList<>();
        script.forEach(c -> ea.addAll(a.handle(c)));
        script.forEach(c -> eb.addAll(b.handle(c)));

        List<Event> trades = ea.stream()
                .filter(e -> e.getPayloadCase() == Event.PayloadCase.TRADE_EXECUTED).toList();
        assertEquals(2, trades.size());
        assertNotEquals(trades.get(0).getTradeExecuted().getTradeId(),
                trades.get(1).getTradeExecuted().getTradeId());

        for (int i = 0; i < ea.size(); i++) {
            assertEquals(ea.get(i), eb.get(i));
        }
    }

    // --------------------------------------------------------- quote quantity

    @Test
    @DisplayName("a market order carrying an explicit time in force is rejected")
    void market_order_with_time_in_force_is_rejected() {
        List<Event> events = engine.handle(
                place(UUID.randomUUID(), 1, SIDE_BUY, ORDER_TYPE_MARKET, TIME_IN_FORCE_GTC, 0, ONE));

        assertEquals(1, events.size());
        assertEquals(Event.PayloadCase.ORDER_REJECTED, events.get(0).getPayloadCase());
    }

    @Test
    @DisplayName("a quote-quantity market buy fills exactly what its budget affords")
    void quote_quantity_market_order_fills_and_emits_a_trade() {
        engine.handle(place(UUID.randomUUID(), 1, SIDE_SELL, P_63250, ONE));

        List<Event> events = engine.handle(
                placeMarketQuoteQty(UUID.randomUUID(), 2, SIDE_BUY, P_63250));

        assertEquals(1, events.size());
        Event e = events.get(0);
        assertEquals(Event.PayloadCase.TRADE_EXECUTED, e.getPayloadCase());
        assertEquals(ONE, e.getTradeExecuted().getQuantity());
        assertEquals(P_63250, e.getTradeExecuted().getQuoteQuantity());
        assertEquals(0, e.getTradeExecuted().getTakerRemainingQuantity());
    }

    @Test
    @DisplayName("quote_order_qty is rejected on anything but a market order")
    void quote_order_qty_rejected_on_limit() {
        Command command = Command.newBuilder()
                .setCommandId(MatchingEngine.toBytes(UUID.randomUUID()))
                .setSymbol(SYMBOL)
                .setTimestampUs(clock += 1_000)
                .setPlaceOrder(PlaceOrder.newBuilder()
                        .setOrderId(MatchingEngine.toBytes(UUID.randomUUID()))
                        .setUserId(1)
                        .setSide(SIDE_BUY)
                        .setOrderType(ORDER_TYPE_LIMIT)
                        .setTimeInForce(TIME_IN_FORCE_GTC)
                        .setPrice(P_63250)
                        .setQuoteOrderQty(ONE))
                .build();

        List<Event> events = engine.handle(command);

        assertEquals(1, events.size());
        assertEquals(Event.PayloadCase.ORDER_REJECTED, events.get(0).getPayloadCase());
        assertEquals(RejectReason.REJECT_REASON_UNSPECIFIED,
                events.get(0).getOrderRejected().getReason());
    }

    @Test
    @DisplayName("a missing quantity is rejected as a bad command, not thrown")
    void missing_quantity_is_rejected_not_thrown() {
        Command command = Command.newBuilder()
                .setCommandId(MatchingEngine.toBytes(UUID.randomUUID()))
                .setSymbol(SYMBOL)
                .setTimestampUs(clock += 1_000)
                .setPlaceOrder(PlaceOrder.newBuilder()
                        .setOrderId(MatchingEngine.toBytes(UUID.randomUUID()))
                        .setUserId(1)
                        .setSide(SIDE_BUY)
                        .setOrderType(ORDER_TYPE_LIMIT)
                        .setTimeInForce(TIME_IN_FORCE_GTC)
                        .setPrice(P_63250))
                // no quantity, and not quote-quantity either
                .build();

        List<Event> events = engine.handle(command);

        assertEquals(1, events.size());
        assertEquals(Event.PayloadCase.ORDER_REJECTED, events.get(0).getPayloadCase());
    }

    @Test
    @DisplayName("a trade carries the taker's own limit price, so settlement can release the improvement")
    void trade_carries_the_takers_limit_price() {
        engine.handle(place(UUID.randomUUID(), 1, SIDE_SELL, P_63250, ONE));

        List<Event> events = engine.handle(place(UUID.randomUUID(), 2, SIDE_BUY, P_63300, ONE));

        var trade = events.get(0).getTradeExecuted();
        assertEquals(P_63250, trade.getPrice(), "the maker's price");
        assertEquals(P_63300, trade.getTakerLimitPrice(), "the taker's own");
    }

    @Test
    @DisplayName("a cancel says whether its quantity was on the book: a user cancel yes, an IOC remainder no")
    void cancel_events_say_whether_the_quantity_rested() {
        UUID resting = UUID.randomUUID();
        engine.handle(place(resting, 1, SIDE_BUY, P_63250, ONE));

        List<Event> ioc = engine.handle(place(UUID.randomUUID(), 2, SIDE_BUY,
                ORDER_TYPE_LIMIT, TIME_IN_FORCE_IOC, P_63250, ONE));
        List<Event> canceled = engine.handle(cancel(resting, 1));

        assertFalse(ioc.get(0).getOrderCanceled().getRested(), "matched nothing and never rested");
        assertTrue(ioc.get(0).getOrderCanceled().hasPrice(), "yet it carries a price, so the flag is what tells");
        assertTrue(canceled.get(0).getOrderCanceled().getRested());
    }

    @Test
    @DisplayName("under self-trade prevention the expired maker had rested; the cut-short taker had not")
    void self_trade_prevention_cancels_say_who_rested() {
        UUID own = UUID.randomUUID();
        engine.handle(place(own, 1, SIDE_SELL, P_63250, ONE));

        List<Event> events = engine.handle(place(UUID.randomUUID(), 1, SIDE_BUY,
                ORDER_TYPE_LIMIT, TIME_IN_FORCE_GTC, P_63250, ONE,
                SelfTradePreventionMode.SELF_TRADE_PREVENTION_MODE_EXPIRE_BOTH));

        assertEquals(2, events.size());
        assertTrue(events.get(0).getOrderCanceled().getRested(), "the maker");
        assertFalse(events.get(1).getOrderCanceled().getRested(), "the taker");
    }

    // ------------------------------------------------------------------ amend

    private Command amend(UUID orderId, long userId, Long price, Long stopPrice, Long qty,
                          long additionalLocked) {
        AmendOrder.Builder amend = AmendOrder.newBuilder()
                .setOrderId(MatchingEngine.toBytes(orderId))
                .setUserId(userId)
                .setAdditionalLocked(additionalLocked);
        if (price != null) {
            amend.setNewPrice(price);
        }
        if (stopPrice != null) {
            amend.setNewStopPrice(stopPrice);
        }
        if (qty != null) {
            amend.setNewQuantity(qty);
        }
        return Command.newBuilder()
                .setCommandId(MatchingEngine.toBytes(UUID.randomUUID()))
                .setSymbol(SYMBOL)
                .setTimestampUs(clock += 1_000)
                .setAmendOrder(amend)
                .build();
    }

    @Test
    @DisplayName("an amend that keeps priority is one OrderAmended saying so, echoing the extra lock")
    void amend_in_place_emits_one_event() {
        UUID id = UUID.randomUUID();
        engine.handle(place(id, 1, SIDE_SELL, P_63250, 3 * ONE));

        List<Event> events = engine.handle(amend(id, 1, null, null, ONE, 42));

        assertEquals(1, events.size());
        var amended = events.get(0).getOrderAmended();
        assertTrue(amended.getPriorityKept());
        assertEquals(3 * ONE, amended.getOldRemainingQuantity());
        assertEquals(ONE, amended.getNewRemainingQuantity());
        assertEquals(ONE, amended.getRestingQuantity());
        assertEquals(P_63250, amended.getNewPrice());
        assertEquals(42, amended.getAdditionalLocked());
    }

    @Test
    @DisplayName("a crossing amend is OrderAmended, then its trades, and never an OrderAccepted")
    void crossing_amend_emits_amended_then_trades() {
        UUID id = UUID.randomUUID();
        engine.handle(place(UUID.randomUUID(), 2, SIDE_SELL, P_63250, ONE));
        engine.handle(place(id, 1, SIDE_BUY, P_63250 - ONE, 3 * ONE));

        List<Event> events = engine.handle(amend(id, 1, P_63300, null, null, 0));

        assertEquals(List.of(Event.PayloadCase.ORDER_AMENDED, Event.PayloadCase.TRADE_EXECUTED),
                events.stream().map(Event::getPayloadCase).toList());
        var amended = events.get(0).getOrderAmended();
        assertEquals(3 * ONE, amended.getNewRemainingQuantity());
        assertEquals(2 * ONE, amended.getRestingQuantity(), "what is left after the trade");
        assertFalse(amended.getPriorityKept());
        assertEquals(P_63250, events.get(1).getTradeExecuted().getPrice());
    }

    @Test
    @DisplayName("a refused amend is an event too, returning the extra lock, and the order is untouched")
    void refused_amend_emits_a_rejection() {
        List<Event> events = engine.handle(amend(UUID.randomUUID(), 1, P_63250, null, null, 42));

        assertEquals(1, events.size());
        var rejected = events.get(0).getOrderAmendRejected();
        assertEquals(AmendRejectReason.AMEND_REJECT_REASON_ORDER_NOT_FOUND, rejected.getReason());
        assertEquals(42, rejected.getAdditionalLocked());
    }

    @Test
    @DisplayName("nobody can amend another user's order")
    void another_users_order_is_not_found() {
        UUID id = UUID.randomUUID();
        engine.handle(place(id, 1, SIDE_SELL, P_63250, ONE));

        List<Event> events = engine.handle(amend(id, 2, P_63300, null, null, 0));

        assertEquals(AmendRejectReason.AMEND_REJECT_REASON_ORDER_NOT_FOUND,
                events.get(0).getOrderAmendRejected().getReason());
    }

    @Test
    @DisplayName("a stop price cannot be put on a resting order")
    void stop_price_on_a_resting_order_is_refused() {
        UUID id = UUID.randomUUID();
        engine.handle(place(id, 1, SIDE_SELL, P_63250, ONE));

        List<Event> events = engine.handle(amend(id, 1, null, P_63250, null, 0));

        assertEquals(AmendRejectReason.AMEND_REJECT_REASON_FIELD_NOT_AMENDABLE,
                events.get(0).getOrderAmendRejected().getReason());
    }

    @Test
    @DisplayName("a parked stop takes a new stop price and size, reported as pending_trigger")
    void parked_order_amend() {
        trade();
        UUID id = UUID.randomUUID();
        engine.handle(stop(id, 3, SIDE_SELL, P_63250 - ONE, ONE));

        List<Event> events = engine.handle(amend(id, 3, null, P_63250 - 2 * ONE, 2 * ONE, 0));

        var amended = events.get(0).getOrderAmended();
        assertTrue(amended.getPendingTrigger());
        assertEquals(P_63250 - ONE, amended.getOldStopPrice());
        assertEquals(P_63250 - 2 * ONE, amended.getNewStopPrice());
        assertEquals(2 * ONE, amended.getNewQuantity());
        assertEquals(0, amended.getRestingQuantity());
        assertFalse(amended.hasNewPrice());
    }

    @Test
    @DisplayName("a parked stop's new stop price that is already met is refused")
    void parked_order_amend_that_would_trigger_is_refused() {
        trade();
        UUID id = UUID.randomUUID();
        engine.handle(stop(id, 3, SIDE_SELL, P_63250 - ONE, ONE));

        List<Event> events = engine.handle(amend(id, 3, null, P_63250 + ONE, null, 0));

        assertEquals(AmendRejectReason.AMEND_REJECT_REASON_WOULD_TRIGGER_IMMEDIATELY,
                events.get(0).getOrderAmendRejected().getReason());
    }
}
