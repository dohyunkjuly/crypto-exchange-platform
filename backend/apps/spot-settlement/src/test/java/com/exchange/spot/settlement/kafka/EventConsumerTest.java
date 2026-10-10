package com.exchange.spot.settlement.kafka;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.exchange.contracts.spot.v1.Event;
import com.exchange.contracts.spot.v1.OrderCanceled;
import com.exchange.contracts.spot.v1.OrderRejected;
import com.exchange.contracts.spot.v1.OrderStatus;
import com.exchange.contracts.spot.v1.RejectReason;
import com.exchange.contracts.spot.v1.Side;
import com.exchange.contracts.spot.v1.TradeExecuted;
import com.exchange.common.id.Ulid;
import com.exchange.spot.settlement.trade.BalanceEffectService;
import com.exchange.spot.settlement.trade.BalanceMutation;
import com.exchange.spot.settlement.trade.OrderEffectService;
import com.exchange.spot.settlement.trade.OrderEffectService.FillContribution;
import com.exchange.spot.settlement.trade.OrderEffectService.OrderFillContribution;
import com.exchange.spot.symbol.SpotSymbol;
import com.exchange.spot.symbol.SpotSymbolRepository;
import com.exchange.wallet.ledger.ReferenceType;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.kafka.support.Acknowledgment;

/**
 * {@link OrderEffectService}/{@link BalanceEffectService} are mocked -- what's under
 * test is {@link EventConsumer}'s own orchestration: dispatch by payload type, grouping
 * mutations across a whole batch by {@code (userId, assetId)} before applying them, the
 * no-ack-on-any-failure rule, and skipping a malformed record rather than failing the
 * whole batch over it.
 */
@ExtendWith(MockitoExtension.class)
class EventConsumerTest {

    private static final String SYMBOL = "BTCUSDT";

    @Mock
    private SpotSymbolRepository spotSymbols;
    @Mock
    private OrderEffectService orderEffects;
    @Mock
    private BalanceEffectService balanceEffects;
    @Mock
    private Acknowledgment ack;

    private EventConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new EventConsumer(spotSymbols, orderEffects, balanceEffects);
        lenient().when(spotSymbols.findBySymbol(SYMBOL))
                .thenReturn(Optional.of(mock(SpotSymbol.class)));
    }

    @Test
    void onEvents_groupsMutationsFromTwoDifferentEventsByUserAndAssetBeforeApplying() {
        BalanceMutation fromReject = mutation(1L, 10L, "reject-unlock");
        BalanceMutation fromCancelSameKey = mutation(1L, 10L, "cancel-unlock");
        BalanceMutation fromCancelDifferentKey = mutation(2L, 20L, "cancel-unlock-2");

        Event rejectEvent = rejectEvent();
        Event cancelEvent = cancelEvent();
        when(orderEffects.applyReject(any(), any())).thenReturn(List.of(fromReject));
        when(orderEffects.applyCancel(any(), any()))
                .thenReturn(List.of(fromCancelSameKey, fromCancelDifferentKey));

        consumer.onEvents(records(rejectEvent, cancelEvent), ack);

        // Groups are dispatched across virtual threads (see EventConsumer's own
        // javadoc for why that's safe -- disjoint keys, by construction), so which
        // group's applyGroup call lands first is no longer meaningful to assert --
        // only that each group's mutations stayed together, correctly formed, and both
        // groups were applied.
        verify(balanceEffects).applyGroup(List.of(fromReject, fromCancelSameKey));
        verify(balanceEffects).applyGroup(List.of(fromCancelDifferentKey));
        verify(ack).acknowledge();
    }

    @Test
    void onEvents_appliesBothARejectAndACancelInTheSameBatch() {
        // Reject/cancel are now dispatched together across virtual threads (see
        // EventConsumer's own javadoc for why a sequential loop here turned out to
        // matter at volume), so which of the two lands first is no longer meaningful
        // to assert -- only that both are applied, to the right event each.
        Event reject = rejectEvent();
        Event cancel = cancelEvent();
        when(orderEffects.applyReject(any(), any())).thenReturn(List.of());
        when(orderEffects.applyCancel(any(), any())).thenReturn(List.of());

        consumer.onEvents(records(reject, cancel), ack);

        verify(orderEffects).applyReject(any(), eq(reject.getOrderRejected()));
        verify(orderEffects).applyCancel(any(), eq(cancel.getOrderCanceled()));
        verify(ack).acknowledge();
    }

    @Test
    void onEvents_forTwoTradesAgainstTheSameOrder_appliesOneAggregateFillNotTwo() {
        // The exact shape of the whale scenario: two trades in one batch both touch
        // the SAME buyer order (a repeated taker) but two DIFFERENT seller orders --
        // applyFillsForOrder must be called once per DISTINCT order, with that order's
        // contributions from BOTH trades combined, not once per trade.
        UUID buyerOrderId = UUID.randomUUID();
        UUID sellerOrderId1 = UUID.randomUUID();
        UUID sellerOrderId2 = UUID.randomUUID();
        Event trade1 = tradeEvent(buyerOrderId, sellerOrderId1, 1);
        Event trade2 = tradeEvent(buyerOrderId, sellerOrderId2, 2);
        when(orderEffects.recordTrades(any(), any())).thenReturn(List.of());
        when(orderEffects.deriveFillContributions(trade1.getTradeExecuted())).thenReturn(List.of(
                new OrderFillContribution(buyerOrderId,
                        new FillContribution(UUID.randomUUID(), 1L, 1L, false)),
                new OrderFillContribution(sellerOrderId1,
                        new FillContribution(UUID.randomUUID(), 1L, 1L, true))));
        when(orderEffects.deriveFillContributions(trade2.getTradeExecuted())).thenReturn(List.of(
                new OrderFillContribution(buyerOrderId,
                        new FillContribution(UUID.randomUUID(), 1L, 1L, false)),
                new OrderFillContribution(sellerOrderId2,
                        new FillContribution(UUID.randomUUID(), 1L, 1L, true))));

        consumer.onEvents(records(trade1, trade2), ack);

        verify(orderEffects).applyFillsForOrder(eq(buyerOrderId), argThat(list -> list.size() == 2));
        verify(orderEffects).applyFillsForOrder(eq(sellerOrderId1), argThat(list -> list.size() == 1));
        verify(orderEffects).applyFillsForOrder(eq(sellerOrderId2), argThat(list -> list.size() == 1));
        // Both trades share the same symbol -- one aggregate recordTrades call for
        // both, not one call per trade.
        verify(orderEffects, times(1)).recordTrades(any(), argThat(list -> list.size() == 2));
        verify(ack).acknowledge();
    }

    @Test
    void onEvents_whenAGroupFails_doesNotAcknowledgeTheBatch() {
        when(orderEffects.applyReject(any(), any())).thenReturn(List.of(mutation(1L, 10L, "k")));
        doThrow(new IllegalStateException("db write failed"))
                .when(balanceEffects).applyGroup(any());

        consumer.onEvents(records(rejectEvent()), ack);

        verify(ack, never()).acknowledge();
    }

    @Test
    void onEvents_retriesATaskThatFailsOnATransientDeadlockThenAcknowledges() {
        // The real, observed failure this exists for: many independent trades landing
        // together means many different users touching wallet_balances for the first
        // time at once, which can genuinely deadlock two unrelated INSERTs. The
        // deadlocked group's own retry must succeed without the batch ever aborting.
        when(orderEffects.applyReject(any(), any())).thenReturn(List.of(mutation(1L, 10L, "k")));
        doThrow(new CannotAcquireLockException("deadlock"))
                .doThrow(new CannotAcquireLockException("deadlock"))
                .doNothing()
                .when(balanceEffects).applyGroup(any());

        consumer.onEvents(records(rejectEvent()), ack);

        verify(balanceEffects, times(3)).applyGroup(any());
        verify(ack).acknowledge();
    }

    @Test
    void onEvents_exhaustsRetriesOnARepeatedDeadlockAndDoesNotAcknowledge() {
        when(orderEffects.applyReject(any(), any())).thenReturn(List.of(mutation(1L, 10L, "k")));
        doThrow(new CannotAcquireLockException("deadlock")).when(balanceEffects).applyGroup(any());

        consumer.onEvents(records(rejectEvent()), ack);

        verify(balanceEffects, times(4)).applyGroup(any()); // 1 initial + 3 retries
        verify(ack, never()).acknowledge();
    }

    @Test
    void onEvents_doesNotRetryANonTransientFailure() {
        when(orderEffects.applyReject(any(), any())).thenReturn(List.of(mutation(1L, 10L, "k")));
        doThrow(new IllegalStateException("not an order")).when(balanceEffects).applyGroup(any());

        consumer.onEvents(records(rejectEvent()), ack);

        verify(balanceEffects, times(1)).applyGroup(any());
        verify(ack, never()).acknowledge();
    }

    @Test
    void onEvents_skipsAMalformedRecordButStillProcessesTheRest() {
        Event valid = rejectEvent();
        when(orderEffects.applyReject(any(), any())).thenReturn(List.of());

        List<ConsumerRecord<String, byte[]>> records = new ArrayList<>();
        records.add(new ConsumerRecord<>("t", 0, 0, "k", new byte[] {-1, -2, -3}));
        records.add(new ConsumerRecord<>("t", 0, 1, "k", valid.toByteArray()));

        consumer.onEvents(records, ack);

        verify(orderEffects).applyReject(any(), eq(valid.getOrderRejected()));
        verify(ack).acknowledge();
    }

    private static BalanceMutation mutation(long userId, long assetId, String key) {
        return new BalanceMutation(BalanceMutation.Kind.UNLOCK, userId, assetId, 1L,
                ReferenceType.ORDER, "ref", key);
    }

    /** A market MySQL does not know can never be settled; skipping it keeps the rest of the partition moving. */
    @Test
    void onEvents_skipsEventsForAnUnknownSymbolAndStillAcknowledges() {
        Event orphan = rejectEvent().toBuilder().setSymbol("GONEUSDT").build();
        when(spotSymbols.findBySymbol("GONEUSDT")).thenReturn(Optional.empty());
        when(orderEffects.applyReject(any(), any())).thenReturn(List.of());

        consumer.onEvents(records(orphan, rejectEvent()), ack);

        verify(orderEffects, times(1)).applyReject(any(), any());
        verify(ack).acknowledge();
    }

    /** No mode allows one; a self-trade on the stream is skipped, not retried forever. */
    @Test
    void onEvents_skipsASelfTradeAndStillAcknowledges() {
        UUID a = UUID.randomUUID(); UUID b = UUID.randomUUID();
        Event trade = tradeEvent(a, b, 1);
        Event selfTrade = trade.toBuilder().setTradeExecuted(
                trade.getTradeExecuted().toBuilder().setTakerUserId(7).setMakerUserId(7)).build();
        consumer.onEvents(records(selfTrade), ack);

        // Nothing left to settle in the batch, and it is still done with.
        verify(orderEffects, never()).recordTrades(any(), any());
        verify(orderEffects, never()).deriveFillContributions(any());
        verify(ack).acknowledge();
    }

    /** A fill that follows an amend to a larger size would be an overfill against the old one. */
    @Test
    void onEvents_appliesAnAmendBeforeAnyFillOfTheBatch_evenOneThatPrecedesIt() {
        UUID buyer = UUID.randomUUID();
        Event amended = Event.newBuilder().setSymbol(SYMBOL).setSequence(2)
                .setCommandId(bytes(UUID.randomUUID()))
                .setOrderAmended(com.exchange.contracts.spot.v1.OrderAmended.newBuilder()
                        .setOrderId(bytes(buyer))).build();
        when(orderEffects.applyAmend(any(), any(), any())).thenReturn(List.of());
        when(orderEffects.recordTrades(any(), any())).thenReturn(List.of());
        when(orderEffects.deriveFillContributions(any())).thenReturn(List.of());

        consumer.onEvents(records(tradeEvent(buyer, UUID.randomUUID(), 1), amended), ack);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(orderEffects);
        order.verify(orderEffects).applyAmend(any(), any(), any());
        order.verify(orderEffects).recordTrades(any(), any());
        verify(ack).acknowledge();
    }

    private static Event tradeEvent(UUID buyerOrderId, UUID sellerOrderId, long sequence) {
        TradeExecuted trade = TradeExecuted.newBuilder()
                .setTradeId(bytes(UUID.randomUUID()))
                .setTakerOrderId(bytes(buyerOrderId))
                .setMakerOrderId(bytes(sellerOrderId))
                .setTakerUserId(1L)
                .setMakerUserId(2L)
                .setTakerSide(Side.SIDE_BUY)
                .setPrice(1L)
                .setQuantity(1L)
                .setQuoteQuantity(1L)
                .setTakerFeeBps(0)
                .setMakerFeeBps(0)
                .setTakerStatus(OrderStatus.ORDER_STATUS_PARTIALLY_FILLED)
                .setMakerStatus(OrderStatus.ORDER_STATUS_FILLED)
                .build();
        return Event.newBuilder()
                .setSymbol(SYMBOL)
                .setSequence(sequence)
                .setTradeExecuted(trade)
                .build();
    }

    private static com.google.protobuf.ByteString bytes(UUID id) {
        return com.google.protobuf.ByteString.copyFrom(Ulid.toBytes(id));
    }

    private static Event rejectEvent() {
        return Event.newBuilder()
                .setSymbol(SYMBOL)
                .setSequence(1)
                .setOrderRejected(OrderRejected.newBuilder()
                        .setUserId(1L)
                        .setReason(RejectReason.REJECT_REASON_SELF_TRADE)
                        .setMessage("x")
                        .build())
                .build();
    }

    private static Event cancelEvent() {
        return Event.newBuilder()
                .setSymbol(SYMBOL)
                .setSequence(2)
                .setOrderCanceled(OrderCanceled.newBuilder()
                        .setUserId(2L)
                        .setReason(com.exchange.contracts.spot.v1.CancelReason.CANCEL_REASON_USER_REQUESTED)
                        .build())
                .build();
    }

    private static List<ConsumerRecord<String, byte[]>> records(Event... events) {
        List<ConsumerRecord<String, byte[]>> records = new ArrayList<>();
        int offset = 0;
        for (Event event : events) {
            records.add(new ConsumerRecord<>("exchange.spot.events.v1", 0, offset++,
                    SYMBOL, event.toByteArray()));
        }
        return records;
    }
}
