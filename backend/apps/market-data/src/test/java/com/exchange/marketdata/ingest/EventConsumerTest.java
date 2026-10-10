package com.exchange.marketdata.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.exchange.common.kafka.Topics;
import com.exchange.contracts.spot.v1.CancelReason;
import com.exchange.contracts.spot.v1.Event;
import com.exchange.contracts.spot.v1.OrderAccepted;
import com.exchange.contracts.spot.v1.OrderCanceled;
import com.exchange.contracts.spot.v1.OrderRejected;
import com.exchange.contracts.spot.v1.OrderType;
import com.exchange.contracts.spot.v1.RejectReason;
import com.exchange.contracts.spot.v1.Side;
import com.exchange.contracts.spot.v1.TradeExecuted;
import com.exchange.marketdata.candle.Market;
import com.exchange.marketdata.depth.BookSide;
import com.exchange.marketdata.ingest.CandleWriter.TradeTick;
import com.exchange.marketdata.ingest.DepthWriter.LevelDelta;
import com.google.protobuf.ByteString;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

/**
 * Both writers are mocked -- what is under test is the decoding between an {@code Event}
 * and the two writers' inputs, which is where a book gets silently corrupted rather than
 * loudly broken. Every assertion here is about a rule stated in {@code events.proto}.
 */
@ExtendWith(MockitoExtension.class)
class EventConsumerTest {

    private static final String SYMBOL = "BTCUSDT";
    private static final long ONE = 100_000_000L;
    private static final long PRICE = 100 * ONE;

    @Mock
    private CandleWriter candles;

    @Mock
    private DepthWriter depth;

    @Mock
    private TradeTapeWriter tape;

    @Mock
    private Acknowledgment ack;

    private EventConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new EventConsumer(candles, depth, tape);
    }

    @Test
    void anAcceptAddsWhatRestedToItsOwnSide() {
        consume(accepted(1, Side.SIDE_BUY, PRICE, 5 * ONE, 3 * ONE));

        assertThat(deltas()).containsExactly(
                new LevelDelta(Market.SPOT, SYMBOL, 1, BookSide.BID, PRICE, 3 * ONE));
    }

    /** {@code resting_quantity}, not {@code quantity}: an order that crossed on arrival
     *  put only its remainder on the book, and the part that traded is reported separately
     *  as a fill. Adding the full quantity would double-count every marketable limit. */
    @Test
    void anAcceptUsesRestingQuantityNotTheOrdersFullSize() {
        consume(accepted(1, Side.SIDE_SELL, PRICE, 10 * ONE, 2 * ONE));

        assertThat(deltas()).singleElement()
                .returns(2 * ONE, LevelDelta::quantityDelta)
                .returns(BookSide.ASK, LevelDelta::side);
    }

    /**
     * The maker-side flip, and the single easiest thing to get backwards here. A taker who
     * BOUGHT was filled by a resting ASK, so the ask side is what loses quantity. Copying
     * {@code taker_side} straight across would take liquidity off the half of the book
     * that was never involved -- growing one side and starving the other, with no error.
     */
    @Test
    void aBuyingTakerRemovesFromTheAskSide() {
        consume(trade(1, Side.SIDE_BUY, PRICE, 2 * ONE));

        assertThat(deltas()).containsExactly(
                new LevelDelta(Market.SPOT, SYMBOL, 1, BookSide.ASK, PRICE, -2 * ONE));
    }

    @Test
    void aSellingTakerRemovesFromTheBidSide() {
        consume(trade(1, Side.SIDE_SELL, PRICE, 2 * ONE));

        assertThat(deltas()).containsExactly(
                new LevelDelta(Market.SPOT, SYMBOL, 1, BookSide.BID, PRICE, -2 * ONE));
    }

    @Test
    void aCancelRemovesExactlyWhatLeftTheBook() {
        consume(canceled(1, Side.SIDE_BUY, PRICE, 4 * ONE));

        assertThat(deltas()).containsExactly(
                new LevelDelta(Market.SPOT, SYMBOL, 1, BookSide.BID, PRICE, -4 * ONE));
    }

    /**
     * A market order priced in the quote asset never rests, so when it runs out of
     * affordable depth its cancel reports {@code canceled_quantity} in the QUOTE asset --
     * see {@code OrderCanceled}'s own comment. Absent price is what marks those. Treating
     * it as base quantity would subtract roughly the price in size from a level the order
     * was never on: at 100/unit, a 400-quote cancel would remove 400 units instead of 4.
     */
    /** An IOC remainder, or a taker cut short by self-trade prevention: it has a price, but the
     *  quantity at that level belongs to other orders that are still there. */
    @Test
    void aCancelOfQuantityThatNeverRestedChangesNoLevel() {
        consume(canceled(1, Side.SIDE_BUY, PRICE, 4 * ONE, false));

        assertThat(deltas()).isEmpty();
    }

    @Test
    void aCancelWithNoPriceNeverTouchedTheBook() {
        consume(Event.newBuilder()
                .setSymbol(SYMBOL)
                .setSequence(1)
                .setOrderCanceled(OrderCanceled.newBuilder()
                        .setOrderId(id())
                        .setUserId(1L)
                        .setReason(CancelReason.CANCEL_REASON_IOC_REMAINDER)
                        .setCanceledQuantity(400 * ONE)
                        .setSide(Side.SIDE_BUY))
                .build());

        assertThat(deltas()).isEmpty();
    }

    /** Zero-quantity remainders are never accepted at all, so one reaching here means the
     *  order rested nothing and no level moved. */
    @Test
    void anAcceptThatRestedNothingChangesNoLevel() {
        consume(accepted(1, Side.SIDE_BUY, PRICE, 5 * ONE, 0));

        assertThat(deltas()).isEmpty();
    }

    /** A market order has no price because it never rests -- it crosses or it does not
     *  happen. There is no level for it to be added to. */
    @Test
    void anAcceptWithNoPriceChangesNoLevel() {
        consume(Event.newBuilder()
                .setSymbol(SYMBOL)
                .setSequence(1)
                .setOrderAccepted(OrderAccepted.newBuilder()
                        .setOrderId(id())
                        .setUserId(1L)
                        .setSide(Side.SIDE_BUY)
                        .setOrderType(OrderType.ORDER_TYPE_MARKET)
                        .setQuantity(5 * ONE)
                        .setRestingQuantity(5 * ONE))
                .build());

        assertThat(deltas()).isEmpty();
    }

    /** A rejection is a fact that nothing happened: the order never reached the book. */
    @Test
    void aRejectionChangesNoLevel() {
        consume(Event.newBuilder()
                .setSymbol(SYMBOL)
                .setSequence(1)
                .setOrderRejected(OrderRejected.newBuilder()
                        .setOrderId(id())
                        .setUserId(1L)
                        .setReason(RejectReason.REJECT_REASON_WOULD_TAKE))
                .build());

        assertThat(deltas()).isEmpty();
    }

    /**
     * The tape carries a fill stripped of everything private -- no user ids, no fee rates.
     * Those are on the wire and are the one thing that must never reach a browser, so this
     * asserts the shape rather than trusting the mapper.
     */
    @Test
    void aTapeEntryCarriesOnlyPublicFields() {
        consume(trade(4, Side.SIDE_SELL, PRICE, 2 * ONE));

        ArgumentCaptor<List<TradeTapeWriter.TapeEntry>> entries = ArgumentCaptor.captor();
        verify(tape).apply(entries.capture());
        assertThat(entries.getValue()).singleElement().satisfies(entry -> {
            assertThat(entry.symbol()).isEqualTo(SYMBOL);
            assertThat(entry.trade().sequence()).isEqualTo(4L);
            assertThat(entry.trade().price()).isEqualTo(PRICE);
            assertThat(entry.trade().quantity()).isEqualTo(2 * ONE);
            assertThat(entry.trade().takerSide()).isEqualTo("SELL");
        });
    }

    /** Candles and the tape take only fills. An order resting or being withdrawn moves no
     *  volume and prints no price, so none of it belongs on a chart or a tape. */
    @Test
    void onlyFillsReachTheCandleWriter() {
        consume(accepted(1, Side.SIDE_BUY, PRICE, 5 * ONE, 5 * ONE),
                trade(2, Side.SIDE_BUY, PRICE, 2 * ONE),
                canceled(3, Side.SIDE_BUY, PRICE, 3 * ONE));

        ArgumentCaptor<List<TradeTick>> ticks = ArgumentCaptor.captor();
        verify(candles).apply(ticks.capture());
        assertThat(ticks.getValue()).singleElement()
                .returns(2L, TradeTick::sequence)
                .returns(2 * ONE, TradeTick::quantity);
        assertThat(deltas()).hasSize(3);

        ArgumentCaptor<List<TradeTapeWriter.TapeEntry>> prints = ArgumentCaptor.captor();
        verify(tape).apply(prints.capture());
        assertThat(prints.getValue()).singleElement()
                .satisfies(entry -> assertThat(entry.trade().sequence()).isEqualTo(2L));
    }

    /** A malformed record can never parse, so retrying the batch forever would stall both
     *  projections over one bad byte. Skip it and acknowledge the rest. */
    @Test
    void aMalformedRecordIsSkippedAndTheBatchStillAdvances() {
        consumer.onEvents(List.of(
                new ConsumerRecord<>(Topics.SPOT_EVENTS, 0, 0L, SYMBOL,
                        new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF}),
                record(trade(1, Side.SIDE_BUY, PRICE, 2 * ONE), 1L)), ack);

        assertThat(deltas()).hasSize(1);
        verify(ack).acknowledge();
    }

    /** Not acknowledging is the whole retry mechanism: the batch is redelivered on the next
     *  poll, and both writers are idempotent so replaying it is safe. */
    @Test
    void aFailedWriteLeavesTheBatchUnacknowledged() {
        doThrow(new IllegalStateException("mongo is down")).when(depth).apply(anyList());

        consume(trade(1, Side.SIDE_BUY, PRICE, 2 * ONE));

        verify(ack, never()).acknowledge();
    }

    // --- helpers ---

    private static Event amended(long sequence, Side side, long oldPrice, long newPrice,
                                 long oldRemaining, long resting, boolean pendingTrigger) {
        return Event.newBuilder().setSymbol(SYMBOL).setSequence(sequence)
                .setOrderAmended(com.exchange.contracts.spot.v1.OrderAmended.newBuilder()
                        .setOrderId(id()).setSide(side)
                        .setOldPrice(oldPrice).setNewPrice(newPrice)
                        .setOldRemainingQuantity(oldRemaining).setNewRemainingQuantity(resting)
                        .setRestingQuantity(resting).setPendingTrigger(pendingTrigger))
                .build();
    }

    @Test
    void anAmendToANewPriceLeavesOneLevelAndJoinsAnother() {
        consume(amended(7, Side.SIDE_BUY, PRICE, PRICE + ONE, 3 * ONE, 2 * ONE, false));

        assertThat(deltas()).extracting(LevelDelta::price, LevelDelta::quantityDelta)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(PRICE, -3 * ONE),
                        org.assertj.core.groups.Tuple.tuple(PRICE + ONE, 2 * ONE));
        assertThat(deltas()).allMatch(d -> d.side() == BookSide.BID && d.sequence() == 7);
    }

    @Test
    void anAmendAtTheSamePriceChangesThatLevelByTheDifference() {
        consume(amended(7, Side.SIDE_SELL, PRICE, PRICE, 3 * ONE, ONE, false));

        assertThat(deltas()).hasSize(1);
        assertThat(deltas().get(0).quantityDelta()).isEqualTo(-2 * ONE);
        assertThat(deltas().get(0).side()).isEqualTo(BookSide.ASK);
    }

    /** It crossed and filled completely: it left its old level and rests nowhere. */
    @Test
    void anAmendThatTradedAwayEntirelyOnlyLeavesItsOldLevel() {
        consume(amended(7, Side.SIDE_BUY, PRICE, PRICE + ONE, 3 * ONE, 0, false));

        assertThat(deltas()).hasSize(1);
        assertThat(deltas().get(0).quantityDelta()).isEqualTo(-3 * ONE);
    }

    @Test
    void anAmendOfAParkedOrderChangesNoLevel() {
        consume(amended(7, Side.SIDE_SELL, PRICE, PRICE + ONE, ONE, ONE, true));

        assertThat(deltas()).isEmpty();
    }

    private void consume(Event... events) {
        List<ConsumerRecord<String, byte[]>> records = new java.util.ArrayList<>();
        long offset = 0;
        for (Event event : events) {
            records.add(record(event, offset++));
        }
        consumer.onEvents(records, ack);
    }

    private static ConsumerRecord<String, byte[]> record(Event event, long offset) {
        return new ConsumerRecord<>(Topics.SPOT_EVENTS, 0, offset, SYMBOL, event.toByteArray());
    }

    private List<LevelDelta> deltas() {
        ArgumentCaptor<List<LevelDelta>> captured = ArgumentCaptor.captor();
        verify(depth).apply(captured.capture());
        return captured.getValue();
    }

    private static ByteString id() {
        return ByteString.copyFrom(new byte[16]);
    }

    private static Event accepted(long sequence, Side side, long price, long quantity,
                                  long restingQuantity) {
        return Event.newBuilder()
                .setSymbol(SYMBOL)
                .setSequence(sequence)
                .setOrderAccepted(OrderAccepted.newBuilder()
                        .setOrderId(id())
                        .setUserId(1L)
                        .setSide(side)
                        .setOrderType(OrderType.ORDER_TYPE_LIMIT)
                        .setPrice(price)
                        .setQuantity(quantity)
                        .setRestingQuantity(restingQuantity))
                .build();
    }

    private static Event trade(long sequence, Side takerSide, long price, long quantity) {
        return Event.newBuilder()
                .setSymbol(SYMBOL)
                .setSequence(sequence)
                .setTradeExecuted(TradeExecuted.newBuilder()
                        .setTradeId(id())
                        .setTakerOrderId(id())
                        .setMakerOrderId(id())
                        .setTakerUserId(1L)
                        .setMakerUserId(2L)
                        .setTakerSide(takerSide)
                        .setPrice(price)
                        .setQuantity(quantity)
                        .setQuoteQuantity(price / ONE * quantity))
                .build();
    }

    private static Event canceled(long sequence, Side side, long price, long quantity) {
        return canceled(sequence, side, price, quantity, true);
    }

    private static Event canceled(long sequence, Side side, long price, long quantity, boolean rested) {
        return Event.newBuilder()
                .setSymbol(SYMBOL)
                .setSequence(sequence)
                .setOrderCanceled(OrderCanceled.newBuilder()
                        .setOrderId(id())
                        .setUserId(1L)
                        .setReason(CancelReason.CANCEL_REASON_USER_REQUESTED)
                        .setCanceledQuantity(quantity)
                        .setPrice(price)
                        .setSide(side)
                        .setRested(rested))
                .build();
    }
}
