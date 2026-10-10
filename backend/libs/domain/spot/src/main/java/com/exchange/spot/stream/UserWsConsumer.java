package com.exchange.spot.stream;

import com.exchange.spot.order.dto.OrderSnapshot;
import com.exchange.marketdata.stream.WsMessage;
import com.exchange.common.kafka.Topics;
import com.exchange.common.id.Ulid;
import com.exchange.contracts.spot.v1.Event;
import com.exchange.contracts.spot.v1.OrderAccepted;
import com.exchange.contracts.spot.v1.OrderAmendRejected;
import com.exchange.contracts.spot.v1.OrderAmended;
import com.exchange.contracts.spot.v1.OrderType;
import com.exchange.contracts.spot.v1.OrderCanceled;
import com.exchange.contracts.spot.v1.OrderRejected;
import com.exchange.contracts.spot.v1.Side;
import com.exchange.contracts.spot.v1.TradeExecuted;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Reads {@code exchange.spot.events.v1} and pushes each user their own order updates.
 *
 * <p>The fourth independent consumer of this topic, and the second read-only one.
 * {@code spot-settlement} moves money from it, {@code market-data} builds projections,
 * {@code MarketWsConsumer} feeds public charts; this one writes nothing and sends only to
 * the person an event belongs to.
 *
 * <p><b>Every instance reads every event and most of them are somebody else's.</b> That is
 * not waste to be optimised away -- a user's connection is on one instance while their
 * orders hash to whichever partition the engine chose, so any instance may need to deliver
 * any event. {@link UserFanout#sendTo} returns on a single map probe when nobody here is
 * that user, which is the common case.
 *
 * <p><b>Never blocks and never retries.</b> Delivery happens on this listener thread, so
 * anything slow here delays every other user on this instance; the handler buffers per
 * connection and drops stragglers rather than making this thread wait. There is no
 * unacknowledged-batch redelivery either: replaying an order update from a minute ago would
 * put a stale status on a live panel, and the panel is refetched over REST on reconnect
 * anyway. The socket is a freshness layer, not a record -- {@code /bff/orders} is the record.
 *
 * <p><b>A trade goes to two people.</b> Both sides of a fill get their own frame, each
 * describing their own order and nothing about the other's. That the two are never crossed
 * is the one failure here that is a breach rather than a glitch.
 */
@Component
public class UserWsConsumer {

    private static final Logger log = LoggerFactory.getLogger(UserWsConsumer.class);

    private static final String TYPE = "orderUpdate";

    private final UserFanout sockets;

    public UserWsConsumer(UserFanout sockets) {
        this.sockets = sockets;
    }

    /**
     * {@code groupId} is a SpEL reference so it resolves exactly once, to the per-instance
     * id -- see {@link UserWsConsumerGroup}.
     *
     * <p>{@code auto.offset.reset=latest} on the listener rather than in application.yml
     * because it contradicts the app-wide default deliberately: a brand-new group starting
     * at {@code earliest} would replay every order this exchange has ever processed into
     * whoever happened to be connected at startup.
     */
    @KafkaListener(topics = Topics.SPOT_EVENTS,
            groupId = "#{@userWsConsumerGroup.id}",
            properties = {"auto.offset.reset=latest"})
    public void onEvent(ConsumerRecord<String, byte[]> record) {
        Event event;
        try {
            event = Event.parseFrom(record.value());
        } catch (InvalidProtocolBufferException e) {
            log.error("skipping malformed event at partition {} offset {}",
                    record.partition(), record.offset(), e);
            return;
        }
        Instant at = microsToInstant(event.getTimestampUs());

        switch (event.getPayloadCase()) {
            case ORDER_ACCEPTED -> {
                OrderAccepted accepted = event.getOrderAccepted();
                String orderId = id(accepted.getOrderId());
                String status = accepted.getPendingTrigger() ? "PENDING_TRIGGER" : "NEW";
                send(accepted.getUserId(), new OrderUpdateData(orderId, event.getSymbol(), status,
                        null, null, null, null, null, null,
                        accepted.hasClientOrderId() ? accepted.getClientOrderId() : null, at,
                        describesWholeOrder(accepted)
                                ? OrderSnapshot.accepted(orderId, event.getSymbol(), accepted, status, at)
                                : null));
            }
            case ORDER_REJECTED -> {
                OrderRejected rejected = event.getOrderRejected();
                send(rejected.getUserId(), new OrderUpdateData(
                        id(rejected.getOrderId()), event.getSymbol(), "REJECTED",
                        null, null, null, null,
                        rejected.getReason().name(), null,
                        rejected.hasClientOrderId() ? rejected.getClientOrderId() : null, at, null));
            }
            case ORDER_CANCELED -> {
                OrderCanceled canceled = event.getOrderCanceled();
                send(canceled.getUserId(), new OrderUpdateData(
                        id(canceled.getOrderId()), event.getSymbol(), "CANCELED",
                        OrderUpdateData.decimalOrNull(canceled.getFilledQuantity()),
                        OrderUpdateData.decimalOrNull(canceled.getCanceledQuantity()),
                        null, null, null, canceled.getReason().name(),
                        canceled.hasClientOrderId() ? canceled.getClientOrderId() : null, at, null));
            }
            case ORDER_AMENDED -> {
                OrderAmended amended = event.getOrderAmended();
                long filled = amended.getNewQuantity() - amended.getNewRemainingQuantity();
                String status = amended.getPendingTrigger() ? "PENDING_TRIGGER"
                        : filled > 0 ? "PARTIALLY_FILLED" : "NEW";
                send(amended.getUserId(), new OrderUpdateData(
                        id(amended.getOrderId()), event.getSymbol(), status,
                        amended.getPendingTrigger() ? null : OrderUpdateData.decimalOrNull(filled),
                        OrderUpdateData.decimalOrNull(amended.getNewRemainingQuantity()),
                        null, null, null, null,
                        amended.hasClientOrderId() ? amended.getClientOrderId() : null, at, null,
                        OrderAmendData.of(amended)));
            }
            case ORDER_AMEND_REJECTED -> {
                OrderAmendRejected rejected = event.getOrderAmendRejected();
                send(rejected.getUserId(), new OrderUpdateData(
                        id(rejected.getOrderId()), event.getSymbol(), null,
                        null, null, null, null, null, null,
                        rejected.hasClientOrderId() ? rejected.getClientOrderId() : null, at, null,
                        OrderAmendData.of(rejected)));
            }
            // Two frames, one each, built independently. Reusing one for both sides is the
            // mistake that would show a user the counterparty's order.
            case TRADE_EXECUTED -> {
                TradeExecuted trade = event.getTradeExecuted();
                send(trade.getTakerUserId(), fill(event, trade, true, at));
                send(trade.getMakerUserId(), fill(event, trade, false, at));
            }
            default -> { }
        }
    }

    /**
     * One side's view of a fill.
     *
     * <p>Everything comes from that side's own fields -- its order id, its status, its
     * remaining quantity. The price and quantity of the fill itself are shared and public
     * (they are on the tape a moment later), but the counterparty's id, status and fee rate
     * are on this same message and none of them appear here.
     */
    private static OrderUpdateData fill(Event event, TradeExecuted trade, boolean taker,
                                        Instant at) {
        return new OrderUpdateData(
                id(taker ? trade.getTakerOrderId() : trade.getMakerOrderId()),
                event.getSymbol(),
                (taker ? trade.getTakerStatus() : trade.getMakerStatus()).name()
                        .replace("ORDER_STATUS_", ""),
                null,
                OrderUpdateData.decimalOrNull(taker
                        ? trade.getTakerRemainingQuantity()
                        : trade.getMakerRemainingQuantity()),
                OrderUpdateData.decimalOrNull(trade.getPrice()),
                OrderUpdateData.decimalOrNull(trade.getQuantity()),
                null, null, null, at, null);
    }

    /**
     * Whether the event alone is the whole row. A parked order and an untouched resting limit
     * are. An order that filled on arrival is not (no average price here), nor a stop that
     * just fired and rested (the resting order no longer carries its stop price).
     */
    static boolean describesWholeOrder(OrderAccepted accepted) {
        if (accepted.getPendingTrigger()) {
            return true;
        }
        OrderType type = accepted.getOrderType();
        return (type == OrderType.ORDER_TYPE_LIMIT || type == OrderType.ORDER_TYPE_LIMIT_MAKER)
                && accepted.getRestingQuantity() == accepted.getQuantity();
    }

    private void send(long userId, OrderUpdateData update) {
        sockets.sendTo(userId, WsMessage.push(TYPE, update));
    }

    private static String id(ByteString orderId) {
        return Ulid.toUuid(orderId.toByteArray()).toString();
    }

    private static Instant microsToInstant(long timestampUs) {
        return Instant.EPOCH.plus(timestampUs, ChronoUnit.MICROS);
    }
}
