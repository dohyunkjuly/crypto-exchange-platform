package com.exchange.marketdata.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.exchange.marketdata.candle.Candle;
import com.exchange.marketdata.candle.CandleInterval;
import com.exchange.marketdata.candle.Market;
import com.exchange.marketdata.ingest.CandleWriter.TradeTick;
import java.time.Instant;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * {@link MongoTemplate} is mocked -- what is under test is {@link CandleWriter}'s own
 * decisions: which trades get folded into which bucket, which get dropped as already
 * applied, and what the resulting update actually says. Whether Mongo honours a
 * {@code $max} is Mongo's problem, not this class's.
 */
@ExtendWith(MockitoExtension.class)
class CandleWriterTest {

    private static final String SYMBOL = "BTCUSDT";
    private static final Instant AT = Instant.parse("2026-09-05T14:37:42Z");

    @Mock
    private MongoTemplate mongo;

    private CandleWriter writer;

    @BeforeEach
    void setUp() {
        writer = new CandleWriter(mongo);
    }

    @Test
    void foldsEveryTradeIntoOneUpsertPerIntervalRatherThanOnePerTrade() {
        when(mongo.find(any(Query.class), eq(Candle.class), anyString())).thenReturn(List.of());

        writer.apply(List.of(
                tick(1, AT, 100_00000000L, 2_00000000L, 200_00000000L),
                tick(2, AT.plusSeconds(5), 110_00000000L, 1_00000000L, 110_00000000L)));

        // Two trades, six intervals -- six writes, not twelve.
        verify(mongo, org.mockito.Mockito.times(CandleInterval.values().length))
                .upsert(any(Query.class), any(Update.class), eq(Candle.class), anyString());
    }

    @Test
    void aggregatesOhlcvCorrectlyWithinOneBucket() {
        when(mongo.find(any(Query.class), eq(Candle.class), anyString())).thenReturn(List.of());

        writer.apply(List.of(
                tick(1, AT, 100_00000000L, 2_00000000L, 200_00000000L),
                tick(2, AT.plusSeconds(5), 130_00000000L, 1_00000000L, 130_00000000L),
                tick(3, AT.plusSeconds(9), 90_00000000L, 3_00000000L, 270_00000000L)));

        Document update = captureUpdateFor(CandleInterval.ONE_MINUTE);
        Document setOnInsert = update.get("$setOnInsert", Document.class);
        Document max = update.get("$max", Document.class);
        Document min = update.get("$min", Document.class);
        Document inc = update.get("$inc", Document.class);
        Document set = update.get("$set", Document.class);

        assertThat(setOnInsert.get("open")).isEqualTo(100_00000000L);   // first trade
        assertThat(max.get("high")).isEqualTo(130_00000000L);
        assertThat(min.get("low")).isEqualTo(90_00000000L);
        assertThat(set.get("close")).isEqualTo(90_00000000L);           // last trade
        assertThat(inc.get("volume")).isEqualTo(6_00000000L);           // 2 + 1 + 3
        assertThat(inc.get("quoteVolume")).isEqualTo(600_00000000L);
        assertThat(inc.get("tradeCount")).isEqualTo(3);
        assertThat(set.get("lastSequence")).isEqualTo(3L);              // highest applied
    }

    @Test
    void skipsTradesAtOrBelowABucketsWatermark() {
        // The redelivery case: sequences 1 and 2 already landed in every bucket. Only 3
        // is new, so only its volume may be added -- adding 1 and 2 again would inflate
        // the chart with no error anywhere.
        when(mongo.find(any(Query.class), eq(Candle.class), anyString()))
                .thenReturn(everyBucketAt(2L));

        writer.apply(List.of(
                tick(1, AT, 100_00000000L, 2_00000000L, 200_00000000L),
                tick(2, AT.plusSeconds(5), 130_00000000L, 1_00000000L, 130_00000000L),
                tick(3, AT.plusSeconds(9), 90_00000000L, 3_00000000L, 270_00000000L)));

        Document update = captureUpdateFor(CandleInterval.ONE_MINUTE);
        assertThat(update.get("$inc", Document.class).get("volume")).isEqualTo(3_00000000L);
        assertThat(update.get("$inc", Document.class).get("tradeCount")).isEqualTo(1);
        assertThat(update.get("$set", Document.class).get("lastSequence")).isEqualTo(3L);
    }

    @Test
    void writesNothingWhenEveryTradeWasAlreadyApplied() {
        when(mongo.find(any(Query.class), eq(Candle.class), anyString()))
                .thenReturn(everyBucketAt(99L));

        writer.apply(List.of(tick(1, AT, 100_00000000L, 2_00000000L, 200_00000000L)));

        verify(mongo, never()).upsert(any(Query.class), any(Update.class), eq(Candle.class), anyString());
    }

    @Test
    void writesNothingForAnEmptyBatch() {
        writer.apply(List.of());

        verify(mongo, never()).find(any(Query.class), eq(Candle.class), anyString());
        verify(mongo, never()).upsert(any(Query.class), any(Update.class), eq(Candle.class), anyString());
    }

    @Test
    void guardsEveryUpsertWithTheWatermarkCondition() {
        // Belt and braces for the rebalance window, where two consumers can briefly own
        // the same partition: even if the read above raced, the update itself must refuse
        // to apply twice.
        when(mongo.find(any(Query.class), eq(Candle.class), anyString())).thenReturn(List.of());

        writer.apply(List.of(tick(7, AT, 100_00000000L, 2_00000000L, 200_00000000L)));

        ArgumentCaptor<Query> queries = ArgumentCaptor.forClass(Query.class);
        verify(mongo, org.mockito.Mockito.atLeastOnce())
                .upsert(queries.capture(), any(Update.class), eq(Candle.class), anyString());
        assertThat(queries.getValue().getQueryObject().toJson())
                .contains("lastSequence")
                .contains("$lt");
    }

    @Test
    void keepsSpotAndFuturesApartForTheSameSymbolAndMinute() {
        // BTCUSDT is both a spot market and a perpetual, at different prices. Without the
        // product in the id these two ticks would share a document, and the upsert would
        // sum their volume and take high/low across two unrelated price series -- silently,
        // with no error to notice. So: two ticks, same symbol, same minute, different
        // products must produce writes to two different bucket ids.
        when(mongo.find(any(Query.class), eq(Candle.class), anyString())).thenReturn(List.of());

        writer.apply(List.of(
                new TradeTick(Market.SPOT, SYMBOL, 1, AT,
                        100_00000000L, 1_00000000L, 100_00000000L),
                new TradeTick(Market.FUTURES, SYMBOL, 1, AT,
                        101_00000000L, 5_00000000L, 505_00000000L)));

        ArgumentCaptor<Query> queries = ArgumentCaptor.forClass(Query.class);
        verify(mongo, org.mockito.Mockito.atLeastOnce())
                .upsert(queries.capture(), any(Update.class), eq(Candle.class), anyString());
        List<String> ids = queries.getAllValues().stream()
                .map(q -> q.getQueryObject().toJson())
                .toList();

        String spotMinute = Candle.idOf(Market.SPOT, SYMBOL, CandleInterval.ONE_MINUTE,
                CandleInterval.ONE_MINUTE.bucketStart(AT));
        String futuresMinute = Candle.idOf(Market.FUTURES, SYMBOL, CandleInterval.ONE_MINUTE,
                CandleInterval.ONE_MINUTE.bucketStart(AT));

        assertThat(spotMinute).isNotEqualTo(futuresMinute);
        assertThat(ids).anyMatch(id -> id.contains(spotMinute));
        assertThat(ids).anyMatch(id -> id.contains(futuresMinute));
        // Six intervals per product, so twelve writes -- not six shared ones.
        verify(mongo, org.mockito.Mockito.times(CandleInterval.values().length * 2))
                .upsert(any(Query.class), any(Update.class), eq(Candle.class), anyString());
    }

    /** Every interval's bucket for {@link #AT}, already advanced to {@code sequence}. */
    private static List<Candle> everyBucketAt(long sequence) {
        return java.util.Arrays.stream(CandleInterval.values())
                .map(interval -> stored(Candle.idOf(Market.SPOT, SYMBOL, interval, interval.bucketStart(AT)),
                        sequence))
                .toList();
    }

    /** Candle has no public constructor (it is written only through upserts), so a stored
     *  document is stood up reflectively rather than by widening the entity's API purely
     *  for a test. */
    private static Candle stored(String id, long lastSequence) {
        try {
            var constructor = Candle.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            Candle candle = constructor.newInstance();
            set(candle, "id", id);
            set(candle, "lastSequence", lastSequence);
            return candle;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void set(Candle candle, String field, Object value)
            throws ReflectiveOperationException {
        var f = Candle.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(candle, value);
    }

    private Document captureUpdateFor(CandleInterval interval) {
        ArgumentCaptor<Query> queries = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<Update> updates = ArgumentCaptor.forClass(Update.class);
        verify(mongo, org.mockito.Mockito.atLeastOnce())
                .upsert(queries.capture(), updates.capture(), eq(Candle.class), anyString());

        String wanted = Candle.idOf(Market.SPOT, SYMBOL, interval, interval.bucketStart(AT));
        List<Query> capturedQueries = queries.getAllValues();
        for (int i = 0; i < capturedQueries.size(); i++) {
            if (capturedQueries.get(i).getQueryObject().toJson().contains(wanted)) {
                return updates.getAllValues().get(i).getUpdateObject();
            }
        }
        throw new AssertionError("no upsert issued for " + wanted);
    }

    private static TradeTick tick(long sequence, Instant at, long price, long qty, long quoteQty) {
        return new TradeTick(Market.SPOT, SYMBOL, sequence, at, price, qty, quoteQty);
    }
}
