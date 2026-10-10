package com.exchange.marketdata.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.exchange.marketdata.candle.Market;
import com.exchange.marketdata.ingest.TradeTapeWriter.TapeEntry;
import com.exchange.marketdata.tape.PublicTrade;
import com.exchange.marketdata.tape.TradeTape;
import java.time.Instant;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * What the update actually says: one write per symbol, capped in the push itself, guarded by
 * the watermark. Whether Mongo honours a {@code $slice} is Mongo's problem.
 */
@ExtendWith(MockitoExtension.class)
class TradeTapeWriterTest {

    private static final String COLLECTION = "spot_market_trades";
    private static final long ONE = 100_000_000L;

    @Mock
    private MongoTemplate mongo;

    private TradeTapeWriter writer;

    @BeforeEach
    void setUp() {
        writer = new TradeTapeWriter(mongo);
    }

    /** A busy batch is a write per market, not a write per fill -- the difference between
     *  a dozen updates and two thousand. */
    @Test
    void oneWritePerSymbolNotPerTrade() {
        writer.apply(List.of(
                entry("BTCUSDT", 1), entry("BTCUSDT", 2), entry("BTCUSDT", 3),
                entry("ETHUSDT", 1), entry("ETHUSDT", 2)));

        verify(mongo, times(2))
                .upsert(any(Query.class), any(Update.class), eq(TradeTape.class), anyString());
    }

    /**
     * The cap lives in the update, so the document cannot grow past it even for a moment
     * and nothing has to come along later to trim it. Negative, because the array is oldest
     * first and it is the newest that must survive -- a positive slice would keep the
     * oldest trades forever and silently stop showing new ones.
     */
    @Test
    void thePushKeepsOnlyTheNewestTradesAndCapsInTheSameUpdate() {
        writer.apply(List.of(entry("BTCUSDT", 1), entry("BTCUSDT", 2)));

        // Asserted on the rendered update rather than the raw document: a $push carrying
        // modifiers is held as Update$Modifiers, not BSON, until Spring serialises it.
        String rendered = captureUpdateObject().toString();
        assertThat(rendered).contains("$push").contains("trades");
        assertThat(rendered).contains("$slice").contains(String.valueOf(-TradeTape.CAPACITY));
    }

    /** Appending is not idempotent: without this a redelivered batch prints every trade in
     *  it twice, with no error and no log line. */
    @Test
    void everyWriteIsGuardedByTheWatermark() {
        writer.apply(List.of(entry("BTCUSDT", 7), entry("BTCUSDT", 9)));

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(mongo).upsert(query.capture(), any(Update.class), eq(TradeTape.class),
                eq(COLLECTION));
        String json = query.getValue().getQueryObject().toJson();
        assertThat(json).contains("lastSequence").contains("$lt").contains("$or");

        // The highest in the batch, not the first: a lower watermark would let the batch
        // through again and duplicate it.
        assertThat(captureUpdate().get("$set", Document.class).get("lastSequence"))
                .isEqualTo(9L);
    }

    @Test
    void writesNothingForAnEmptyBatch() {
        writer.apply(List.of());

        verify(mongo, never())
                .upsert(any(Query.class), any(Update.class), eq(TradeTape.class), anyString());
    }

    // --- helpers ---

    /** A redelivery of a batch already on the tape: the guard matches nothing and the upsert
     *  tries to insert a duplicate. That is a no-op, not a poison batch that blocks the partition. */
    @Test
    void aDuplicateKeyIsAnAlreadyAppliedBatchNotAnError() {
        when(mongo.upsert(any(Query.class), any(Update.class), eq(TradeTape.class), anyString()))
                .thenThrow(new DuplicateKeyException("E11000"));

        writer.apply(List.of(entry("BTCUSDT", 7)));

        verify(mongo, times(1)).upsert(any(Query.class), any(Update.class), eq(TradeTape.class), anyString());
    }

    /** Unless the market started over at sequence 1: then the stored tape is the old history and is replaced. */
    @Test
    void aBatchStartingAtSequenceOneReplacesAnOldTape() {
        when(mongo.upsert(any(Query.class), any(Update.class), eq(TradeTape.class), anyString()))
                .thenThrow(new DuplicateKeyException("E11000")).thenReturn(null);

        writer.apply(List.of(entry("BTCUSDT", 1), entry("BTCUSDT", 2)));

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(mongo, times(2)).upsert(query.capture(), any(Update.class), eq(TradeTape.class), anyString());
        assertThat(query.getAllValues().get(1).getQueryObject().toJson()).doesNotContain("lastSequence");
    }

    private Document captureUpdate() {
        return (Document) captured().getUpdateObject();
    }

    /** The whole update, modifiers included -- {@code getUpdateObject()} leaves a $push's
     *  modifiers as a Spring type rather than BSON, so a slice can only be read as text. */
    private Update captureUpdateObject() {
        return captured();
    }

    private Update captured() {
        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        verify(mongo).upsert(any(Query.class), update.capture(), eq(TradeTape.class),
                eq(COLLECTION));
        return update.getValue();
    }

    private static TapeEntry entry(String symbol, long sequence) {
        return new TapeEntry(Market.SPOT, symbol, new PublicTrade(
                sequence, 100 * ONE, ONE, 100 * ONE, "BUY",
                Instant.EPOCH.plusSeconds(sequence)));
    }
}
