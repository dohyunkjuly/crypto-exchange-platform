package com.exchange.marketdata.ingest;

import com.exchange.common.kafka.Topics;
import com.exchange.contracts.spot.v1.Event;
import com.exchange.contracts.spot.v1.OrderAccepted;
import com.exchange.contracts.spot.v1.OrderAmended;
import com.exchange.contracts.spot.v1.OrderCanceled;
import com.exchange.contracts.spot.v1.Side;
import com.exchange.contracts.spot.v1.TradeExecuted;
import com.exchange.marketdata.candle.Market;
import com.exchange.marketdata.depth.BookSide;
import com.exchange.marketdata.ingest.CandleWriter.TradeTick;
import com.exchange.marketdata.ingest.DepthWriter.LevelDelta;
import com.exchange.marketdata.ingest.TradeTapeWriter.TapeEntry;
import com.exchange.marketdata.tape.PublicTrade;
import com.google.protobuf.InvalidProtocolBufferException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * This app's read of {@code exchange.spot.events.v1}, feeding both projections it builds.
 *
 * <p><b>One consumer, three writers.</b> Candles, depth and the public trade tape read the
 * same events under one group and one offset, rather than a listener each. Separate groups
 * would multiply this app's read of the topic to build three things from the same records,
 * and would let the projections sit at different offsets -- so a chart, the book beneath it
 * and the tape beside it could disagree about what has happened, which is exactly the sort
 * of inconsistency nobody thinks to look for.
 *
 * <p>They take different slices. Candles and the tape need only {@code TradeExecuted}: an
 * order resting or being withdrawn moves no volume and prints no price. Depth needs the
 * other three as well -- most orders never trade, and a feed of fills alone cannot build a
 * book at all.
 *
 * <p>Failure handling follows {@code spot-settlement}'s precedent, with one deliberate
 * difference in what it means. A malformed record is a poison pill -- logged and skipped,
 * because retrying will never parse it. Anything else leaves the batch unacknowledged for
 * redelivery, which is safe because both writers are idempotent. The difference is the
 * stakes: a batch stuck here means charts and the book lag and then catch up on their own,
 * whereas the same situation in settlement means balances are behind. That is the entire
 * reason these are two processes.
 */
@Component
public class EventConsumer {

    private static final Logger log = LoggerFactory.getLogger(EventConsumer.class);

    /** Market.SPOT throughout, because this listener is on exchange.spot.events.v1. A
     *  futures listener would sit beside it and pass Market.FUTURES -- the reason ids carry
     *  a product at all, see Market's javadoc. */
    private static final Market MARKET = Market.SPOT;

    private final CandleWriter candles;
    private final DepthWriter depth;
    private final TradeTapeWriter tape;

    public EventConsumer(CandleWriter candles, DepthWriter depth, TradeTapeWriter tape) {
        this.candles = candles;
        this.depth = depth;
        this.tape = tape;
    }

    @KafkaListener(topics = Topics.SPOT_EVENTS)
    public void onEvents(List<ConsumerRecord<String, byte[]>> records, Acknowledgment ack) {
        List<TradeTick> ticks = new ArrayList<>(records.size());
        List<LevelDelta> deltas = new ArrayList<>(records.size());
        List<TapeEntry> prints = new ArrayList<>(records.size());

        for (ConsumerRecord<String, byte[]> record : records) {
            Event event;
            try {
                event = Event.parseFrom(record.value());
            } catch (InvalidProtocolBufferException e) {
                log.error("skipping malformed event at partition {} offset {}",
                        record.partition(), record.offset(), e);
                continue;
            }
            if (event.getPayloadCase() == Event.PayloadCase.TRADE_EXECUTED) {
                ticks.add(toTick(event));
                prints.add(toTapeEntry(event));
            }
            deltas.addAll(toDeltas(event));
        }

        try {
            candles.apply(ticks);
            depth.apply(deltas);
            tape.apply(prints);
        } catch (RuntimeException e) {
            log.error("failed to build market data for a batch of {} events; not "
                    + "acknowledging, will redeliver", records.size(), e);
            return;
        }
        ack.acknowledge();
    }

    /** Strips the fill down to what a stranger may see -- no user ids, no fees. See
     *  {@code PublicTrade} for why that subset is the whole point of the type. */
    private static TapeEntry toTapeEntry(Event event) {
        TradeExecuted trade = event.getTradeExecuted();
        return new TapeEntry(MARKET, event.getSymbol(), new PublicTrade(
                event.getSequence(),
                trade.getPrice(),
                trade.getQuantity(),
                trade.getQuoteQuantity(),
                trade.getTakerSide() == Side.SIDE_BUY ? "BUY" : "SELL",
                microsToInstant(event.getTimestampUs())));
    }

    private static TradeTick toTick(Event event) {
        TradeExecuted trade = event.getTradeExecuted();
        return new TradeTick(MARKET, event.getSymbol(), event.getSequence(),
                microsToInstant(event.getTimestampUs()),
                trade.getPrice(), trade.getQuantity(), trade.getQuoteQuantity());
    }

    /**
     * The levels this event changed: none, one, or two for an amend that moved an order from
     * one price to another.
     *
     * <p>Only quantity that was on the book can leave it, and the engine says which: an
     * accept carries {@code resting_quantity}, a cancel carries {@code rested}. A price on
     * the event is not enough -- an IOC remainder has one and never rested -- and neither is
     * its absence: a quote-sized market order's cancel has none and reports its
     * {@code canceled_quantity} in the QUOTE asset.
     */
    private static List<LevelDelta> toDeltas(Event event) {
        if (event.getPayloadCase() == Event.PayloadCase.ORDER_AMENDED) {
            OrderAmended amended = event.getOrderAmended();
            // A parked order was never on the book, before or after.
            if (amended.getPendingTrigger()) {
                return List.of();
            }
            BookSide side = sideOf(amended.getSide());
            if (amended.getOldPrice() == amended.getNewPrice()) {
                long change = amended.getRestingQuantity() - amended.getOldRemainingQuantity();
                return change == 0 ? List.of()
                        : nonNull(delta(event, side, amended.getNewPrice(), change));
            }
            List<LevelDelta> legs = new ArrayList<>(nonNull(
                    delta(event, side, amended.getOldPrice(), -amended.getOldRemainingQuantity())));
            if (amended.getRestingQuantity() > 0) {
                legs.addAll(nonNull(delta(event, side, amended.getNewPrice(), amended.getRestingQuantity())));
            }
            return legs;
        }
        return nonNull(toDelta(event));
    }

    private static List<LevelDelta> nonNull(LevelDelta delta) {
        return delta == null ? List.of() : List.of(delta);
    }

    private static LevelDelta toDelta(Event event) {
        return switch (event.getPayloadCase()) {
            case ORDER_ACCEPTED -> {
                OrderAccepted accepted = event.getOrderAccepted();
                yield accepted.hasPrice() && accepted.getRestingQuantity() > 0
                        ? delta(event, sideOf(accepted.getSide()), accepted.getPrice(),
                                accepted.getRestingQuantity())
                        : null;
            }
            // The price is the MAKER's, and so is the side: the maker was resting there and
            // the taker crossed to it, so a buy taker consumes an ASK. Copying taker_side
            // across would take liquidity off whichever half of the book was not involved.
            case TRADE_EXECUTED -> {
                TradeExecuted trade = event.getTradeExecuted();
                yield delta(event, makerSideOf(trade.getTakerSide()), trade.getPrice(),
                        -trade.getQuantity());
            }
            // Only what was on the book leaves it. An IOC remainder or a taker cut short by
            // self-trade prevention carries a price too, but subtracting it would take other
            // users' quantity off a level the order was never on.
            case ORDER_CANCELED -> {
                OrderCanceled canceled = event.getOrderCanceled();
                yield canceled.getRested() && canceled.hasPrice() && canceled.getCanceledQuantity() > 0
                        ? delta(event, sideOf(canceled.getSide()), canceled.getPrice(),
                                -canceled.getCanceledQuantity())
                        : null;
            }
            // A rejected order never reached the book, so there is nothing to remove.
            default -> null;
        };
    }

    private static LevelDelta delta(Event event, BookSide side, long price, long quantityDelta) {
        return side == null ? null
                : new LevelDelta(MARKET, event.getSymbol(), event.getSequence(),
                        side, price, quantityDelta);
    }

    /** Null for {@code SIDE_UNSPECIFIED} or a value this build does not know, which skips
     *  the event rather than guessing a side and corrupting the book. */
    private static BookSide sideOf(Side side) {
        return switch (side) {
            case SIDE_BUY -> BookSide.BID;
            case SIDE_SELL -> BookSide.ASK;
            default -> null;
        };
    }

    /** The maker sits opposite the taker: a taker buying was filled by a resting ask. */
    private static BookSide makerSideOf(Side takerSide) {
        return switch (takerSide) {
            case SIDE_BUY -> BookSide.ASK;
            case SIDE_SELL -> BookSide.BID;
            default -> null;
        };
    }

    private static Instant microsToInstant(long timestampUs) {
        return Instant.EPOCH.plus(timestampUs, ChronoUnit.MICROS);
    }
}
