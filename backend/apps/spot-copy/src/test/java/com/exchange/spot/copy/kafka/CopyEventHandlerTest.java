package com.exchange.spot.copy.kafka;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;

import com.exchange.common.id.Ulid;
import com.exchange.contracts.spot.v1.Event;
import com.exchange.contracts.spot.v1.OrderAccepted;
import com.exchange.contracts.spot.v1.OrderAmended;
import com.exchange.contracts.spot.v1.OrderCanceled;
import com.exchange.contracts.spot.v1.OrderRejected;
import com.exchange.contracts.spot.v1.OrderStatus;
import com.exchange.contracts.spot.v1.TradeExecuted;
import com.exchange.copy.leadorder.CopyLeadOrder;
import com.exchange.copy.leadorder.CopyLeadOrderRepository;
import com.exchange.copy.order.CopyOrder;
import com.exchange.copy.order.CopyOrderReason;
import com.exchange.copy.order.CopyOrderRepository;
import com.exchange.copy.order.CopyOrderStatus;
import com.exchange.spot.copy.copy.CopyService;
import com.exchange.spot.copy.lead.LeadOrderService;
import com.exchange.spot.order.Side;
import com.google.protobuf.ByteString;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

/** Out of everything the exchange does, only a lead order's and a copy's events are acted on, in order. */
class CopyEventHandlerTest {

    private static final long ONE = 100_000_000L;

    private final CopyLeadOrderRepository leadOrders = Mockito.mock(CopyLeadOrderRepository.class);
    private final CopyOrderRepository copyOrders = Mockito.mock(CopyOrderRepository.class);
    private final LeadOrderService leads = Mockito.mock(LeadOrderService.class);
    private final CopyService copies = Mockito.mock(CopyService.class);
    private final CopyEventHandler handler = new CopyEventHandler(leadOrders, copyOrders, leads, copies);

    private final CopyLeadOrder lead = new CopyLeadOrder(Ulid.randomUuid(), Ulid.randomUuid(), 10L, Side.BUY,
            CopyLeadOrder.WHOLE / 2, 500 * ONE, 5 * ONE);
    private final CopyOrder copy = new CopyOrder(Ulid.randomUuid(), Ulid.randomUuid(), lead.getOrderId(),
            Ulid.randomUuid(), 10L, Side.BUY, CopyOrderStatus.PLACED, null);
    private final UUID stranger = Ulid.randomUuid();

    @Test
    @DisplayName("events about nobody's order cost the two lookups and nothing else")
    void strangersAreIgnored() {
        handler.handle(List.of(trade(1, stranger, Ulid.randomUuid(), OrderStatus.ORDER_STATUS_FILLED,
                OrderStatus.ORDER_STATUS_FILLED), cancel(2, stranger)));

        Mockito.verifyNoInteractions(leads, copies);
    }

    @Test
    @DisplayName("a batch with no order in it at all does not touch the database")
    void nothingToLookUp() {
        handler.handle(List.of(Event.newBuilder().setSequence(1).setOrderAccepted(OrderAccepted.getDefaultInstance()).build()));

        Mockito.verifyNoInteractions(leadOrders, copyOrders, leads, copies);
    }

    @Test
    @DisplayName("a lead order's trades are passed on whether it took or made, whatever status the trade carries")
    void leadTrades() {
        known(lead, null);

        handler.handle(List.of(
                trade(5, lead.getOrderId(), stranger, OrderStatus.ORDER_STATUS_PARTIALLY_FILLED, OrderStatus.ORDER_STATUS_FILLED),
                trade(6, stranger, lead.getOrderId(), OrderStatus.ORDER_STATUS_FILLED, OrderStatus.ORDER_STATUS_FILLED)));

        InOrder order = Mockito.inOrder(leads);
        order.verify(leads).traded(lead, 5, 2 * ONE, 200 * ONE);
        order.verify(leads).traded(lead, 6, 2 * ONE, 200 * ONE);
    }

    @Test
    @DisplayName("a cancel or a rejection closes a lead order; an amend the engine took is followed")
    void leadCloseAndAmend() {
        known(lead, null);
        OrderAmended amended = OrderAmended.newBuilder().setOrderId(bytes(lead.getOrderId())).setNewQuantity(ONE).build();

        handler.handle(List.of(Event.newBuilder().setSequence(1).setOrderAmended(amended).build(),
                cancel(2, lead.getOrderId())));
        handler.handle(List.of(Event.newBuilder().setSequence(3)
                .setOrderRejected(OrderRejected.newBuilder().setOrderId(bytes(lead.getOrderId()))).build()));

        Mockito.verify(leads).amended(lead, amended);
        Mockito.verify(leads, Mockito.times(2)).closed(lead);
    }

    @Test
    @DisplayName("a lead order already copied is not followed again when its events come back")
    void aCopiedLeadOrderIsDone() {
        lead.setCopiedAt(Instant.now());
        known(lead, null);

        handler.handle(List.of(cancel(2, lead.getOrderId())));

        Mockito.verifyNoInteractions(leads);
    }

    @Test
    @DisplayName("a copy's trade is its success; its cancel or rejection, its failure and the reason")
    void copyOutcomes() {
        known(null, copy);

        handler.handle(List.of(trade(7, copy.getOrderId(), stranger, OrderStatus.ORDER_STATUS_PARTIALLY_FILLED,
                OrderStatus.ORDER_STATUS_FILLED), cancel(8, copy.getOrderId())));
        handler.handle(List.of(Event.newBuilder().setSequence(9)
                .setOrderRejected(OrderRejected.newBuilder().setOrderId(bytes(copy.getOrderId()))).build()));

        InOrder order = Mockito.inOrder(copies);
        order.verify(copies).filled(copy);
        order.verify(copies).notFilled(copy, CopyOrderReason.NOT_FILLED);
        order.verify(copies).notFilled(copy, CopyOrderReason.REJECTED);
        Mockito.verify(leads, Mockito.never()).traded(any(), anyLong(), anyLong(), anyLong());
    }

    private void known(CopyLeadOrder leadOrder, CopyOrder copyOrder) {
        Mockito.when(leadOrders.findAllById(any())).thenReturn(leadOrder == null ? List.of() : List.of(leadOrder));
        Mockito.when(copyOrders.findByOrderIdIn(any())).thenReturn(copyOrder == null ? List.of() : List.of(copyOrder));
    }

    private static Event trade(long sequence, UUID taker, UUID maker, OrderStatus takerStatus, OrderStatus makerStatus) {
        return Event.newBuilder().setSequence(sequence).setTradeExecuted(TradeExecuted.newBuilder()
                .setTakerOrderId(bytes(taker)).setMakerOrderId(bytes(maker)).setQuantity(2 * ONE)
                .setQuoteQuantity(200 * ONE).setTakerStatus(takerStatus).setMakerStatus(makerStatus)).build();
    }

    private static Event cancel(long sequence, UUID orderId) {
        return Event.newBuilder().setSequence(sequence)
                .setOrderCanceled(OrderCanceled.newBuilder().setOrderId(bytes(orderId))).build();
    }

    private static ByteString bytes(UUID id) {
        return ByteString.copyFrom(Ulid.toBytes(id));
    }
}
