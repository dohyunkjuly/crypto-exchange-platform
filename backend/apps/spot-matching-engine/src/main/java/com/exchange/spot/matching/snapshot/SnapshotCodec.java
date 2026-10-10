package com.exchange.spot.matching.snapshot;

import com.exchange.spot.matching.engine.MatchingEngine;

import com.exchange.contracts.spot.v1.BookSnapshot;
import com.exchange.contracts.spot.v1.ConditionalOrder;
import com.exchange.contracts.spot.v1.RestingOrder;
import com.exchange.contracts.spot.v1.SymbolSnapshot;
import com.exchange.spot.matching.book.Order;
import com.exchange.spot.matching.trigger.PendingOrder;
import com.exchange.spot.matching.trigger.TrailingOrder;
import com.google.protobuf.ByteString;
import java.util.ArrayList;
import java.util.List;

/**
 * Converts engine state to and from {@link BookSnapshot}.
 *
 * <p>Kept out of {@link MatchingEngine} so the engine stays a state machine and this stays
 * a pure translation — and so a round-trip test can run without touching either.
 */
public final class SnapshotCodec {

    private SnapshotCodec() {
    }

    public static SymbolSnapshot capture(String symbol, long sequence, long lastTradePrice,
                                  List<Order> resting, List<PendingOrder> pending,
                                  List<TrailingOrder> trailing,
                                  List<ByteString> recentCommandIds) {
        SymbolSnapshot.Builder builder = SymbolSnapshot.newBuilder()
                .setSymbol(symbol)
                .setSequence(sequence)
                .setLastTradePrice(lastTradePrice)
                .addAllRecentCommandIds(recentCommandIds);

        // Order is preserved: position in this list IS time priority.
        for (Order order : resting) {
            builder.addResting(RestingOrder.newBuilder()
                    .setOrderId(MatchingEngine.toBytes(order.id()))
                    .setUserId(order.userId())
                    .setSide(order.side())
                    .setOrderType(order.type())
                    .setTimeInForce(order.timeInForce())
                    .setPrice(order.price())
                    .setQuantity(order.quantity())
                    .setRemaining(order.remaining())
                    .setMakerFeeBps(order.makerFeeBps())
                    .setTakerFeeBps(order.takerFeeBps())
                    .setSelfTradePreventionMode(order.selfTradePreventionMode()));
        }

        for (PendingOrder order : pending) {
            ConditionalOrder.Builder conditional = ConditionalOrder.newBuilder()
                    .setOrderId(MatchingEngine.toBytes(order.id()))
                    .setUserId(order.userId())
                    .setSide(order.side())
                    .setOrderType(order.type())
                    .setTimeInForce(order.timeInForce())
                    .setStopPrice(order.stopPrice())
                    .setLimitPrice(order.limitPrice())
                    .setMakerFeeBps(order.makerFeeBps())
                    .setTakerFeeBps(order.takerFeeBps())
                    .setSelfTradePreventionMode(order.selfTradePreventionMode());
            if (order.isQuoteQuantity()) {
                conditional.setQuoteOrderQty(order.quantity());
            } else {
                conditional.setQuantity(order.quantity());
            }
            if (order.clientOrderId() != null) {
                conditional.setClientOrderId(order.clientOrderId());
            }
            if (order.isTrailing()) {
                conditional.setTrailingDeltaBps(order.trailingDeltaBps());
            }
            builder.addPending(conditional);
        }

        // Actively-trailing orders share the same ConditionalOrder shape, distinguished
        // by extreme_price being set -- see trailingFrom.
        for (TrailingOrder order : trailing) {
            ConditionalOrder.Builder conditional = ConditionalOrder.newBuilder()
                    .setOrderId(MatchingEngine.toBytes(order.id()))
                    .setUserId(order.userId())
                    .setSide(order.side())
                    .setOrderType(order.type())
                    .setTimeInForce(order.timeInForce())
                    .setStopPrice(order.effectiveStopPrice())   // informational only
                    .setLimitPrice(order.limitPrice())
                    .setMakerFeeBps(order.makerFeeBps())
                    .setTakerFeeBps(order.takerFeeBps())
                    .setSelfTradePreventionMode(order.selfTradePreventionMode())
                    .setTrailingDeltaBps(order.trailingDeltaBps())
                    .setExtremePrice(order.extremePrice());
            if (order.isQuoteQuantity()) {
                conditional.setQuoteOrderQty(order.quantity());
            } else {
                conditional.setQuantity(order.quantity());
            }
            if (order.clientOrderId() != null) {
                conditional.setClientOrderId(order.clientOrderId());
            }
            builder.addPending(conditional);
        }
        return builder.build();
    }

    public static List<Order> restingFrom(SymbolSnapshot snapshot) {
        List<Order> orders = new ArrayList<>(snapshot.getRestingCount());
        for (RestingOrder resting : snapshot.getRestingList()) {
            orders.add(Order.restored(
                    MatchingEngine.toUuid(resting.getOrderId()),
                    resting.getUserId(),
                    resting.getSide(),
                    resting.getOrderType(),
                    resting.getTimeInForce(),
                    resting.getPrice(),
                    resting.getQuantity(),
                    resting.getRemaining(),
                    resting.getMakerFeeBps(),
                    resting.getTakerFeeBps(),
                    resting.getSelfTradePreventionMode()));
        }
        return orders;
    }

    /** Plain and activation-gated pending orders only -- see {@link #trailingFrom} for
     *  an order that had already started tracking when the snapshot was taken. */
    public static List<PendingOrder> pendingFrom(SymbolSnapshot snapshot) {
        List<PendingOrder> orders = new ArrayList<>(snapshot.getPendingCount());
        for (ConditionalOrder conditional : snapshot.getPendingList()) {
            if (conditional.hasExtremePrice()) {
                continue;
            }
            orders.add(new PendingOrder(
                    MatchingEngine.toUuid(conditional.getOrderId()),
                    conditional.getUserId(),
                    conditional.getSide(),
                    conditional.getOrderType(),
                    conditional.getTimeInForce(),
                    conditional.getStopPrice(),
                    conditional.getLimitPrice(),
                    conditional.hasQuoteOrderQty()
                            ? conditional.getQuoteOrderQty() : conditional.getQuantity(),
                    conditional.getMakerFeeBps(),
                    conditional.getTakerFeeBps(),
                    conditional.hasClientOrderId() ? conditional.getClientOrderId() : null,
                    conditional.getSelfTradePreventionMode(),
                    conditional.getTrailingDeltaBps(),
                    conditional.hasQuoteOrderQty()));
        }
        return orders;
    }

    /** Orders that had already started trailing when the snapshot was taken --
     *  {@code extreme_price} is what distinguishes these from {@link #pendingFrom}. */
    public static List<TrailingOrder> trailingFrom(SymbolSnapshot snapshot) {
        List<TrailingOrder> orders = new ArrayList<>();
        for (ConditionalOrder conditional : snapshot.getPendingList()) {
            if (!conditional.hasExtremePrice()) {
                continue;
            }
            orders.add(new TrailingOrder(
                    MatchingEngine.toUuid(conditional.getOrderId()),
                    conditional.getUserId(),
                    conditional.getSide(),
                    conditional.getOrderType(),
                    conditional.getTimeInForce(),
                    conditional.getLimitPrice(),
                    conditional.hasQuoteOrderQty()
                            ? conditional.getQuoteOrderQty() : conditional.getQuantity(),
                    conditional.getMakerFeeBps(),
                    conditional.getTakerFeeBps(),
                    conditional.hasClientOrderId() ? conditional.getClientOrderId() : null,
                    conditional.getSelfTradePreventionMode(),
                    conditional.getTrailingDeltaBps(),
                    conditional.hasQuoteOrderQty(),
                    conditional.getExtremePrice()));
        }
        return orders;
    }
}
