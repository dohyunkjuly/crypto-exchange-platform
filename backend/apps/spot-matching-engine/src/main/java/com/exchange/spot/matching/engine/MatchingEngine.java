package com.exchange.spot.matching.engine;

import com.exchange.contracts.spot.v1.AmendOrder;
import com.exchange.contracts.spot.v1.AmendRejectReason;
import com.exchange.contracts.spot.v1.BookSnapshot;
import com.exchange.contracts.spot.v1.CancelAllOrders;
import com.exchange.contracts.spot.v1.CancelScope;
import com.exchange.contracts.spot.v1.CancelOrder;
import com.exchange.contracts.spot.v1.CancelReason;
import com.exchange.contracts.spot.v1.Command;
import com.exchange.contracts.spot.v1.Event;
import com.exchange.contracts.spot.v1.OrderAccepted;
import com.exchange.contracts.spot.v1.OrderAmendRejected;
import com.exchange.contracts.spot.v1.OrderAmended;
import com.exchange.contracts.spot.v1.OrderCanceled;
import com.exchange.contracts.spot.v1.OrderRejected;
import com.exchange.contracts.spot.v1.OrderStatus;
import com.exchange.contracts.spot.v1.OrderType;
import com.exchange.contracts.spot.v1.PlaceOrder;
import com.exchange.contracts.spot.v1.RejectReason;
import com.exchange.contracts.spot.v1.SelfTradePreventionMode;
import com.exchange.contracts.spot.v1.Side;
import com.exchange.contracts.spot.v1.TimeInForce;
import com.exchange.contracts.spot.v1.SymbolSnapshot;
import com.exchange.contracts.spot.v1.TradeExecuted;
import com.exchange.spot.matching.book.AmendResult;
import com.exchange.spot.matching.book.Fill;
import com.exchange.spot.matching.book.MatchResult;
import com.exchange.spot.matching.book.Order;
import com.exchange.spot.matching.trigger.PendingOrder;
import com.exchange.spot.matching.snapshot.SnapshotCodec;
import com.exchange.spot.matching.trigger.TriggerAmendResult;
import com.exchange.spot.matching.trigger.TriggerBook;
import com.google.protobuf.ByteString;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Commands in, events out. The whole engine, with no Kafka in it.
 *
 * <p>Startup, recovery, snapshots and failure behaviour: docs/reference/matching-engine.md.
 *
 * <p>Deliberately free of Spring, the network and the clock, so the entire thing is
 * testable in milliseconds — the Kafka listener around it does nothing but deserialize,
 * call {@link #handle}, publish, and acknowledge.
 *
 * <p><b>A pure function of the command log.</b> Every value it produces — trade ids,
 * sequences, timestamps — is derived from its input. No {@code Instant.now()}, no
 * {@code UUID.randomUUID()}, no database read. Replay the same commands and you get byte
 * identical events, which is the property recovery depends on.
 *
 * <p>Not thread safe. One partition, one thread, by design; see {@code OrderBook}.
 */
public final class MatchingEngine {

    /**
     * How many times one command may fire a chain reaction of conditional orders.
     *
     * <p>Without a limit, a single trade could crash a thin market to zero: it fires a
     * stop, the stop's order trades and moves the price further, that fires more stops,
     * and so on forever. A limit turns a runaway chain into something that just stops
     * partway — visible and fixable, instead of a crash nobody saw coming.
     */
    private static final int MAX_CASCADE_ROUNDS = 8;

    private static final Logger log = LoggerFactory.getLogger(MatchingEngine.class);

    private final Map<String, SymbolState> states = new HashMap<>();

    /**
     * Processes one command and returns everything that happened, in order.
     *
     * <p>Never throws for bad input. A malformed command produces an OrderRejected event
     * rather than an exception, because an exception here would stall the partition —
     * the consumer would retry the same poisoned message forever and no other order on
     * this market would be processed again.
     */
    public List<Event> handle(Command command) {
        return handle(command, 0);
    }

    /**
     * @param partition the command partition this arrived on. Recorded against the symbol
     *                  so a snapshot can describe exactly one partition's markets.
     */
    public List<Event> handle(Command command, int partition) {
        SymbolState state = states.computeIfAbsent(command.getSymbol(),
                symbol -> new SymbolState(symbol, partition));
        List<Event> events = new ArrayList<>();

        // A repeat of a command already processed. Nothing, not a rejection: the first copy
        // produced the events, and this one must not -- a market order matched twice is
        // two sets of real trades, and only a resting order's id would have caught it.
        if (!state.firstSightOf(command.getCommandId())) {
            log.warn("skipping repeated command {} for {}",
                    describe(command.getCommandId()), command.getSymbol());
            return events;
        }

        switch (command.getPayloadCase()) {
            case PLACE_ORDER -> placeOrder(state, command, events);
            case CANCEL_ORDER -> cancelOrder(state, command, events);
            case CANCEL_ALL_ORDERS -> cancelAll(state, command, events);
            case AMEND_ORDER -> amendOrder(state, command, events);
            case PAYLOAD_NOT_SET -> { /* nothing to do; a producer bug, not an order */ }
        }
        return events;
    }

    // ----------------------------------------------------------- place order

    private void placeOrder(SymbolState state, Command command, List<Event> events) {
        PlaceOrder request = command.getPlaceOrder();
        UUID orderId = toUuid(request.getOrderId());

        if (request.getSide() == Side.SIDE_UNSPECIFIED
                || request.getOrderType() == OrderType.ORDER_TYPE_UNSPECIFIED) {
            events.add(rejection(state, command, orderId, request,
                    RejectReason.REJECT_REASON_UNSPECIFIED,
                    "side and order type are required"));
            return;
        }
        // OrderService refuses this too. FOK must leave the book untouched, and EXPIRE_BOTH
        // asks to cancel the resting order.
        if (request.getTimeInForce() == TimeInForce.TIME_IN_FORCE_FOK
                && request.getSelfTradePreventionMode()
                        == SelfTradePreventionMode.SELF_TRADE_PREVENTION_MODE_EXPIRE_BOTH) {
            events.add(rejection(state, command, orderId, request,
                    RejectReason.REJECT_REASON_UNSPECIFIED,
                    "EXPIRE_BOTH cannot be combined with FOK"));
            return;
        }

        // A market order fills whatever it can the instant it arrives and never rests --
        // there is no "how long should this wait" for a client to answer, so unlike every
        // other order type it must NOT carry one. Internally it is always treated as IOC;
        // see Order's constructor.
        boolean tifGiven = request.getTimeInForce() != TimeInForce.TIME_IN_FORCE_UNSPECIFIED;
        if (request.getOrderType() == OrderType.ORDER_TYPE_MARKET ? tifGiven : !tifGiven) {
            events.add(rejection(state, command, orderId, request,
                    RejectReason.REJECT_REASON_UNSPECIFIED,
                    request.getOrderType() == OrderType.ORDER_TYPE_MARKET
                            ? "a market order fills immediately and cannot carry a time in force"
                            : "time in force is required"));
            return;
        }

        // Only a MARKET-firing order's cost is unknown ahead of matching -- MARKET
        // itself, or a plain STOP_LOSS/TAKE_PROFIT, which becomes one when it fires.
        // Anything with a fixed price (LIMIT, or a *_LIMIT conditional) can compute
        // quantity = quoteOrderQty / price itself before ever building a command.
        if (request.hasQuoteOrderQty() && !isQuoteQuantityEligible(request.getOrderType())) {
            events.add(rejection(state, command, orderId, request,
                    RejectReason.REJECT_REASON_UNSPECIFIED,
                    "quote_order_qty is only valid on a market order or a plain "
                            + "stop-loss/take-profit"));
            return;
        }
        // Whichever asset a side gives up is the only one that can be locked exactly
        // ahead of matching -- the field bounding what it RECEIVES is unbounded on the
        // giving-up side. A BUY sized by quantity has an unbounded quote cost if the
        // price is worse than expected; a SELL sized by quote_order_qty has an
        // unbounded base cost the same way, walking deeper into the book to reach the
        // same proceeds target. So on these three types, BUY must carry quote_order_qty
        // and nothing else; SELL must carry quantity and nothing else.
        if (isQuoteQuantityEligible(request.getOrderType())) {
            boolean isBuy = request.getSide() == Side.SIDE_BUY;
            if (isBuy != request.hasQuoteOrderQty()) {
                events.add(rejection(state, command, orderId, request,
                        RejectReason.REJECT_REASON_UNSPECIFIED,
                        isBuy ? "a market-firing BUY requires quote_order_qty"
                              : "a market-firing SELL cannot use quote_order_qty"));
                return;
            }
            if (isBuy && request.getQuantity() > 0) {
                events.add(rejection(state, command, orderId, request,
                        RejectReason.REJECT_REASON_UNSPECIFIED,
                        "quantity and quote_order_qty are mutually exclusive"));
                return;
            }
        }
        // Every other order shape needs an explicit base quantity. Checked here, not left
        // to Order's own constructor, so a malformed command is rejected like any other
        // rather than throwing -- see this method's contract above.
        if (!request.hasQuoteOrderQty() && request.getQuantity() <= 0) {
            events.add(rejection(state, command, orderId, request,
                    RejectReason.REJECT_REASON_UNSPECIFIED,
                    "quantity is required"));
            return;
        }

        // Only a conditional order can trail -- a resting LIMIT's price is already
        // fixed, and a MARKET order fires the instant it arrives, so neither has
        // anything for a trailing stop to mean.
        if (request.hasTrailingDeltaBps() && !isConditional(request.getOrderType())) {
            events.add(rejection(state, command, orderId, request,
                    RejectReason.REJECT_REASON_UNSPECIFIED,
                    "trailing_delta_bps is only valid on a conditional order"));
            return;
        }

        if (isConditional(request.getOrderType())) {
            parkConditional(state, command, orderId, request, events);
            return;
        }

        Order order = new Order(orderId, request.getUserId(), request.getSide(),
                request.getOrderType(), request.getTimeInForce(),
                request.hasPrice() ? request.getPrice() : 0,
                request.hasQuoteOrderQty() ? request.getQuoteOrderQty() : request.getQuantity(),
                request.getMakerFeeBps(), request.getTakerFeeBps(),
                request.getSelfTradePreventionMode(), request.hasQuoteOrderQty());

        MatchResult result = state.book().submit(order);

        if (result.isRejected()) {
            events.add(rejection(state, command, orderId, request, result.rejection(), ""));
            return;
        }

        emitMatchResult(state, command, order, result,
                request.hasClientOrderId() ? request.getClientOrderId() : null, events);

        // Fills moved the price, which may reach conditional orders waiting for it.
        runCascade(state, command, events);
    }

    // ----------------------------------------------------------- amend order

    /**
     * Parked orders first, then the book -- the same order {@link #cancelOrder} looks in.
     * Every outcome is an event, including a refusal: the API answered 202 before this ran,
     * and it locked {@code additional_locked} that settlement can only return if told to.
     */
    private void amendOrder(SymbolState state, Command command, List<Event> events) {
        AmendOrder request = command.getAmendOrder();
        UUID orderId = toUuid(request.getOrderId());
        Long newPrice = request.hasNewPrice() ? request.getNewPrice() : null;
        Long newStopPrice = request.hasNewStopPrice() ? request.getNewStopPrice() : null;
        Long newQuantity = request.hasNewQuantity() ? request.getNewQuantity() : null;
        Long newQuoteQty = request.hasNewQuoteOrderQty() ? request.getNewQuoteOrderQty() : null;

        if ((newPrice != null && newPrice <= 0) || (newStopPrice != null && newStopPrice <= 0)
                || (newQuantity != null && newQuantity <= 0) || (newQuoteQty != null && newQuoteQty <= 0)
                || (newQuantity != null && newQuoteQty != null)) {
            events.add(amendRejected(state, command, request,
                    AmendRejectReason.AMEND_REJECT_REASON_FIELD_NOT_AMENDABLE));
            return;
        }

        PendingOrder parked = state.triggers().find(orderId, request.getUserId());
        if (parked != null) {
            // A parked order has one size field, read as quote or base by how it was placed.
            if ((parked.isQuoteQuantity() ? newQuantity : newQuoteQty) != null) {
                events.add(amendRejected(state, command, request,
                        AmendRejectReason.AMEND_REJECT_REASON_FIELD_NOT_AMENDABLE));
                return;
            }
            TriggerAmendResult result = state.triggers().amend(orderId, newStopPrice, newPrice,
                    parked.isQuoteQuantity() ? newQuoteQty : newQuantity, state.lastTradePrice());
            if (result.isRejected()) {
                events.add(amendRejected(state, command, request, result.rejection()));
                return;
            }
            PendingOrder before = result.before();
            PendingOrder after = result.after();
            OrderAmended.Builder amended = amendedBuilder(request, after.side())
                    .setOldRemainingQuantity(before.quantity())
                    .setNewRemainingQuantity(after.quantity())
                    .setNewQuantity(after.quantity())
                    .setPendingTrigger(true);
            if (after.limitPrice() > 0) {
                amended.setOldPrice(before.limitPrice()).setNewPrice(after.limitPrice());
            }
            if (!after.isTrailing()) {
                amended.setOldStopPrice(before.stopPrice()).setNewStopPrice(after.stopPrice());
            }
            events.add(event(state, command).setOrderAmended(amended).build());
            return;
        }

        Order resting = state.book().find(orderId);
        if (resting == null || resting.userId() != request.getUserId()) {
            events.add(amendRejected(state, command, request,
                    AmendRejectReason.AMEND_REJECT_REASON_ORDER_NOT_FOUND));
            return;
        }
        if (newStopPrice != null || newQuoteQty != null) {
            events.add(amendRejected(state, command, request,
                    AmendRejectReason.AMEND_REJECT_REASON_FIELD_NOT_AMENDABLE));
            return;
        }
        AmendResult result = state.book().amend(orderId, newPrice, newQuantity);
        if (result.isRejected()) {
            events.add(amendRejected(state, command, request, result.rejection()));
            return;
        }
        Order order = result.order();
        MatchResult match = result.match();
        events.add(event(state, command).setOrderAmended(amendedBuilder(request, order.side())
                .setOldPrice(result.oldPrice())
                .setNewPrice(order.price())
                .setOldRemainingQuantity(result.oldRemaining())
                .setNewRemainingQuantity(result.newRemaining())
                .setNewQuantity(order.quantity())
                .setPriorityKept(result.priorityKept())
                .setRestingQuantity(match == null ? result.newRemaining() : match.restingQuantity()))
                .build());
        if (match != null) {
            // OrderAmended already said what rests, so no OrderAccepted: to a depth consumer
            // that would be the same quantity arriving twice.
            emitMatchResult(state, command, order, match,
                    request.hasClientOrderId() ? request.getClientOrderId() : null, events, false);
            runCascade(state, command, events);
        }
    }

    private static OrderAmended.Builder amendedBuilder(AmendOrder request, Side side) {
        OrderAmended.Builder builder = OrderAmended.newBuilder()
                .setOrderId(request.getOrderId())
                .setUserId(request.getUserId())
                .setSide(side)
                .setAdditionalLocked(request.getAdditionalLocked());
        if (request.hasClientOrderId()) {
            builder.setClientOrderId(request.getClientOrderId());
        }
        return builder;
    }

    private Event amendRejected(SymbolState state, Command command, AmendOrder request,
                                AmendRejectReason reason) {
        OrderAmendRejected.Builder builder = OrderAmendRejected.newBuilder()
                .setOrderId(request.getOrderId())
                .setUserId(request.getUserId())
                .setReason(reason)
                .setAdditionalLocked(request.getAdditionalLocked());
        if (request.hasClientOrderId()) {
            builder.setClientOrderId(request.getClientOrderId());
        }
        return event(state, command).setOrderAmendRejected(builder).build();
    }

    /**
     * Turns one MatchResult into events. Shared by the normal path and the cascade, so a
     * triggered order produces exactly the same shape of events as a submitted one --
     * nothing downstream can tell, or needs to.
     */
    private void emitMatchResult(SymbolState state, Command command, Order order,
                                 MatchResult result, String clientOrderId,
                                 List<Event> events) {
        emitMatchResult(state, command, order, result, clientOrderId, events, true);
    }

    private void emitMatchResult(SymbolState state, Command command, Order order,
                                 MatchResult result, String clientOrderId,
                                 List<Event> events, boolean announceRest) {
        // Self-trade prevention removed these before or during matching, so consumers
        // must see them leave the book before the fills that followed.
        for (Order expired : result.expiredMakers()) {
            events.add(event(state, command).setOrderCanceled(OrderCanceled.newBuilder()
                    .setOrderId(toBytes(expired.id()))
                    .setUserId(expired.userId())
                    .setReason(CancelReason.CANCEL_REASON_SELF_TRADE_PREVENTION)
                    .setCanceledQuantity(expired.remaining())
                    .setFilledQuantity(expired.filled())
                    .setPrice(expired.price())
                    .setSide(expired.side())
                    .setRested(true)).build());
        }

        for (Fill fill : result.fills()) {
            state.recordTrade(fill.price());
            events.add(tradeExecuted(state, command, order, fill));
        }

        if (announceRest && result.isRested()) {
            OrderAccepted.Builder accepted = OrderAccepted.newBuilder()
                    .setOrderId(toBytes(order.id()))
                    .setUserId(order.userId())
                    .setSide(order.side())
                    .setOrderType(order.type())
                    .setTimeInForce(order.timeInForce())
                    .setQuantity(order.quantity())
                    .setRestingQuantity(result.restingQuantity());
            if (order.price() > 0) {
                accepted.setPrice(order.price());
            }
            if (clientOrderId != null) {
                accepted.setClientOrderId(clientOrderId);
            }
            events.add(event(state, command).setOrderAccepted(accepted).build());
        }

        // Never rested, so it is cancelled rather than left: either an IOC/market
        // remainder with nowhere to go, or self-trade prevention cutting the taker short.
        if (result.canceledQuantity() > 0) {
            OrderCanceled.Builder canceled = OrderCanceled.newBuilder()
                    .setOrderId(toBytes(order.id()))
                    .setUserId(order.userId())
                    .setReason(result.takerExpiredBySelfTrade()
                            ? CancelReason.CANCEL_REASON_SELF_TRADE_PREVENTION
                            : CancelReason.CANCEL_REASON_IOC_REMAINDER)
                    .setCanceledQuantity(result.canceledQuantity())
                    .setFilledQuantity(order.filled())
                    .setSide(order.side());
            if (order.price() > 0) {
                canceled.setPrice(order.price());
            }
            if (clientOrderId != null) {
                canceled.setClientOrderId(clientOrderId);
            }
            events.add(event(state, command).setOrderCanceled(canceled).build());
        }
    }

    /** Trailing orders no tighter than 0.1% or looser than 20% -- the usual bounds.
     *  Anything tighter would fire on ordinary noise; anything looser barely trails. */
    private static final int MIN_TRAILING_DELTA_BPS = 10;
    private static final int MAX_TRAILING_DELTA_BPS = 2_000;

    /**
     * Parks a conditional order, or refuses it if its condition is already met.
     *
     * <p>An order that would fire immediately is refused, not fired -- the user almost
     * certainly got the direction or price backwards, and if they wanted it to execute
     * right away they'd have placed a market order instead. Same reason a market with no
     * trade history refuses conditional orders: there's no price yet to judge them against.
     */
    private void parkConditional(SymbolState state, Command command, UUID orderId,
                                 PlaceOrder request, List<Event> events) {
        if (request.hasTrailingDeltaBps()
                && (request.getTrailingDeltaBps() < MIN_TRAILING_DELTA_BPS
                        || request.getTrailingDeltaBps() > MAX_TRAILING_DELTA_BPS)) {
            events.add(rejection(state, command, orderId, request,
                    RejectReason.REJECT_REASON_UNSPECIFIED,
                    "trailing_delta_bps must be between " + MIN_TRAILING_DELTA_BPS
                            + " and " + MAX_TRAILING_DELTA_BPS));
            return;
        }
        if (request.hasStopPrice() && request.getStopPrice() <= 0) {
            events.add(rejection(state, command, orderId, request,
                    RejectReason.REJECT_REASON_UNSPECIFIED,
                    "stop_price must be positive"));
            return;
        }
        // A trailing order's stop_price is an optional ACTIVATION price -- absent, it
        // starts tracking immediately. A plain conditional order has no such escape.
        if (!request.hasTrailingDeltaBps() && !request.hasStopPrice()) {
            events.add(rejection(state, command, orderId, request,
                    RejectReason.REJECT_REASON_UNSPECIFIED,
                    "a conditional order needs a stop price"));
            return;
        }

        PendingOrder pending = new PendingOrder(orderId, request.getUserId(),
                request.getSide(), request.getOrderType(), request.getTimeInForce(),
                request.hasStopPrice() ? request.getStopPrice() : 0,
                request.hasPrice() ? request.getPrice() : 0,
                request.hasQuoteOrderQty() ? request.getQuoteOrderQty() : request.getQuantity(),
                request.getMakerFeeBps(), request.getTakerFeeBps(),
                request.hasClientOrderId() ? request.getClientOrderId() : null,
                request.getSelfTradePreventionMode(),
                request.hasTrailingDeltaBps() ? request.getTrailingDeltaBps() : 0,
                request.hasQuoteOrderQty());

        if (TriggerBook.wouldTriggerImmediately(pending, state.lastTradePrice())) {
            events.add(rejection(state, command, orderId, request,
                    RejectReason.REJECT_REASON_WOULD_TRIGGER_IMMEDIATELY,
                    state.hasTraded()
                            ? "the stop price is already on the wrong side of the market"
                            : "this market has not traded, so there is no price to trigger on"));
            return;
        }

        if (pending.isTrailing() && pending.stopPrice() <= 0) {
            state.triggers().activateTrailing(pending, state.lastTradePrice());
        } else {
            state.triggers().park(pending);
        }

        OrderAccepted.Builder accepted = OrderAccepted.newBuilder()
                .setOrderId(toBytes(orderId))
                .setUserId(pending.userId())
                .setSide(pending.side())
                .setOrderType(pending.type())
                .setTimeInForce(pending.timeInForce())
                .setQuantity(pending.quantity())
                // Nothing rests on the book -- it is accepted, waiting, and invisible in
                // depth until it fires.
                .setRestingQuantity(0);
        if (pending.limitPrice() > 0) {
            accepted.setPrice(pending.limitPrice());
        }
        if (pending.clientOrderId() != null) {
            accepted.setClientOrderId(pending.clientOrderId());
        }
        accepted.setPendingTrigger(true);
        if (pending.stopPrice() > 0) {
            accepted.setStopPrice(pending.stopPrice());
        }
        if (pending.isTrailing()) {
            accepted.setTrailingDeltaBps(pending.trailingDeltaBps());
        }
        if (pending.isQuoteQuantity()) {
            accepted.setQuoteOrderQty(pending.quantity());
        }
        events.add(event(state, command).setOrderAccepted(accepted).build());
    }

    /**
     * Fires everything the new price reached, matches each, and repeats while those fills
     * move the price again -- bounded by {@link #MAX_CASCADE_ROUNDS}.
     */
    private void runCascade(SymbolState state, Command command, List<Event> events) {
        for (int round = 0; round < MAX_CASCADE_ROUNDS; round++) {
            List<PendingOrder> fired = state.triggers().onPrice(state.lastTradePrice());
            if (fired.isEmpty()) {
                return;
            }
            long priceBefore = state.lastTradePrice();

            for (PendingOrder pending : fired) {
                Order triggered = pending.toActiveOrder();
                MatchResult result = state.book().submit(triggered);
                emitMatchResult(state, command, triggered, result,
                        pending.clientOrderId(), events);
            }
            if (state.lastTradePrice() == priceBefore) {
                return;   // nothing moved, so nothing new can fire
            }
        }
    }

    private Event tradeExecuted(SymbolState state, Command command, Order taker, Fill fill) {
        Event.Builder builder = event(state, command);
        return builder.setTradeExecuted(TradeExecuted.newBuilder()
                .setTradeId(toBytes(state.tradeId(command.getTimestampUs())))
                .setTakerOrderId(toBytes(taker.id()))
                .setMakerOrderId(toBytes(fill.makerOrderId()))
                .setTakerUserId(taker.userId())
                .setMakerUserId(fill.makerUserId())
                .setTakerSide(taker.side())
                .setPrice(fill.price())
                .setQuantity(fill.quantity())
                .setQuoteQuantity(fill.quoteQuantity())
                .setTakerFeeBps(taker.takerFeeBps())
                // The maker's own rate, from when THEIR order was placed.
                .setMakerFeeBps(fill.makerFeeBps())
                .setTakerLimitPrice(taker.price())
                .setTakerRemainingQuantity(taker.remaining())
                .setMakerRemainingQuantity(fill.makerRemaining())
                .setTakerStatus(taker.isFilled()
                        ? OrderStatus.ORDER_STATUS_FILLED
                        : OrderStatus.ORDER_STATUS_PARTIALLY_FILLED)
                .setMakerStatus(fill.makerFullyFilled()
                        ? OrderStatus.ORDER_STATUS_FILLED
                        : OrderStatus.ORDER_STATUS_PARTIALLY_FILLED))
                .build();
    }

    // ---------------------------------------------------------------- cancel

    private void cancelOrder(SymbolState state, Command command, List<Event> events) {
        CancelOrder request = command.getCancelOrder();
        UUID orderId = toUuid(request.getOrderId());

        // A cancel may target a conditional order that is parked rather than resting.
        PendingOrder pending = state.triggers().cancel(orderId);
        if (pending != null) {
            events.add(event(state, command).setOrderCanceled(OrderCanceled.newBuilder()
                    .setOrderId(toBytes(pending.id()))
                    .setUserId(pending.userId())
                    .setReason(CancelReason.CANCEL_REASON_USER_REQUESTED)
                    .setCanceledQuantity(pending.quantity())
                    .setFilledQuantity(0)
                    .setSide(pending.side())).build());
            return;
        }

        Order removed = state.book().cancel(orderId);

        // Null means it was already filled or already cancelled. A user hitting cancel
        // while their order fills is normal, so this is silence rather than an error.
        if (removed == null) {
            return;
        }
        events.add(canceled(state, command, removed, CancelReason.CANCEL_REASON_USER_REQUESTED,
                request.hasClientOrderId() ? request.getClientOrderId() : null));
    }

    /** UNSPECIFIED on any filter means any: an unfiltered request cancels everything the
     *  user has in this market, exactly as it did before the filters existed. */
    private void cancelAll(SymbolState state, Command command, List<Event> events) {
        CancelAllOrders request = command.getCancelAllOrders();
        Side side = request.getSide() == Side.SIDE_UNSPECIFIED ? null : request.getSide();
        OrderType type = request.getOrderType() == OrderType.ORDER_TYPE_UNSPECIFIED
                ? null : request.getOrderType();
        CancelScope scope = request.getScope();

        if (scope != CancelScope.CANCEL_SCOPE_BOOK) {
            for (PendingOrder pending : state.triggers().cancelAllFor(request.getUserId(), side, type)) {
                events.add(event(state, command).setOrderCanceled(OrderCanceled.newBuilder()
                        .setOrderId(toBytes(pending.id()))
                        .setUserId(pending.userId())
                        .setReason(CancelReason.CANCEL_REASON_USER_REQUESTED)
                        .setCanceledQuantity(pending.quantity())
                        .setFilledQuantity(0)
                        .setSide(pending.side())).build());
            }
        }
        if (scope != CancelScope.CANCEL_SCOPE_TRIGGERS) {
            for (Order removed : state.book().cancelAllFor(request.getUserId(), side, type)) {
                events.add(canceled(state, command, removed,
                        CancelReason.CANCEL_REASON_USER_REQUESTED, null));
            }
        }
    }

    private Event canceled(SymbolState state, Command command, Order order,
                           CancelReason reason, String clientOrderId) {
        OrderCanceled.Builder builder = OrderCanceled.newBuilder()
                .setOrderId(toBytes(order.id()))
                .setUserId(order.userId())
                .setReason(reason)
                .setCanceledQuantity(order.remaining())
                .setFilledQuantity(order.filled())
                .setSide(order.side())
                .setRested(true);
        if (order.price() > 0) {
            builder.setPrice(order.price());
        }
        if (clientOrderId != null) {
            builder.setClientOrderId(clientOrderId);
        }
        return event(state, command).setOrderCanceled(builder).build();
    }

    // --------------------------------------------------------------- helpers

    private Event rejection(SymbolState state, Command command, UUID orderId,
                            PlaceOrder request, RejectReason reason, String message) {
        OrderRejected.Builder builder = OrderRejected.newBuilder()
                .setOrderId(toBytes(orderId))
                .setUserId(request.getUserId())
                .setReason(reason)
                .setMessage(message);
        if (request.hasClientOrderId()) {
            builder.setClientOrderId(request.getClientOrderId());
        }
        return event(state, command).setOrderRejected(builder).build();
    }

    /** Every event carries the symbol, the next sequence, and the command's own time. */
    private Event.Builder event(SymbolState state, Command command) {
        return Event.newBuilder()
                .setSymbol(state.symbol())
                .setSequence(state.nextSequence())
                .setTimestampUs(command.getTimestampUs())
                .setCommandId(command.getCommandId());
    }

    private static boolean isConditional(OrderType type) {
        return switch (type) {
            case ORDER_TYPE_STOP_LOSS, ORDER_TYPE_STOP_LOSS_LIMIT,
                 ORDER_TYPE_TAKE_PROFIT, ORDER_TYPE_TAKE_PROFIT_LIMIT -> true;
            default -> false;
        };
    }

    /** MARKET, and the two conditional types that become one when they fire. The
     *  *_LIMIT conditional variants fire at a fixed price instead, same as LIMIT --
     *  no execution-price ambiguity for quoteOrderQty to solve. */
    private static boolean isQuoteQuantityEligible(OrderType type) {
        return type == OrderType.ORDER_TYPE_MARKET
                || type == OrderType.ORDER_TYPE_STOP_LOSS
                || type == OrderType.ORDER_TYPE_TAKE_PROFIT;
    }

    /** 16 raw bytes to a UUID. Public because anything building or reading these
     *  messages needs the same conversion. */
    public static UUID toUuid(ByteString bytes) {
        ByteBuffer buffer = bytes.asReadOnlyByteBuffer();
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    /** For a log line only: an id of the wrong length must not turn a skip into a throw. */
    private static Object describe(ByteString id) {
        return id.size() == 16 ? toUuid(id) : id;
    }

    public static ByteString toBytes(UUID uuid) {
        ByteBuffer buffer = ByteBuffer.allocate(16);
        buffer.putLong(uuid.getMostSignificantBits());
        buffer.putLong(uuid.getLeastSignificantBits());
        return ByteString.copyFrom(buffer.array());
    }

    // ------------------------------------------------------------- snapshots

    /**
     * Captures every symbol this engine holds.
     *
     * <p>A snapshot is an optimisation rather than part of the state machine -- taking one
     * at a different moment changes nothing about the resulting state -- so unlike
     * everything else here, when it happens does not have to be deterministic.
     */
    public BookSnapshot snapshot(int partition, long offset, long timestampUs) {
        BookSnapshot.Builder builder = BookSnapshot.newBuilder()
                .setPartition(partition)
                .setOffset(offset)
                .setTimestampUs(timestampUs);
        for (SymbolState state : states.values()) {
            // Only this partition's symbols. A snapshot is restored against one seek
            // position, so it must describe exactly what that partition owns.
            if (state.partition() != partition) {
                continue;
            }
            builder.addSymbols(SnapshotCodec.capture(
                    state.symbol(), state.currentSequence(), state.lastTradePrice(),
                    state.book().snapshotOrders(), state.triggers().snapshotPending(),
                    state.triggers().snapshotTrailing(), state.recentCommandIds()));
        }
        return builder.build();
    }

    /**
     * Rebuilds from a snapshot.
     *
     * <p>Called once per assigned partition. Every symbol belongs to exactly one partition,
     * so restoring several snapshots into one engine is expected, not an error -- what
     * would be an error is restoring the SAME symbol twice, which would silently duplicate
     * its resting orders. That's what the guard below checks for.
     */
    public void restore(BookSnapshot snapshot) {
        for (SymbolSnapshot symbolSnapshot : snapshot.getSymbolsList()) {
            if (states.containsKey(symbolSnapshot.getSymbol())) {
                throw new IllegalStateException(
                        "restoring " + symbolSnapshot.getSymbol() + ", which already has state");
            }
            SymbolState state = states.computeIfAbsent(symbolSnapshot.getSymbol(),
                    symbol -> new SymbolState(symbol, snapshot.getPartition()));
            state.book().restore(SnapshotCodec.restingFrom(symbolSnapshot));
            state.triggers().restore(SnapshotCodec.pendingFrom(symbolSnapshot));
            state.triggers().restoreTrailing(SnapshotCodec.trailingFrom(symbolSnapshot));
            state.restoreCounters(symbolSnapshot.getSequence(),
                    symbolSnapshot.getLastTradePrice(), symbolSnapshot.getRecentCommandIdsList());
        }
    }

    /** Test and diagnostic access. */
    public int symbolCount() {
        return states.size();
    }
}
