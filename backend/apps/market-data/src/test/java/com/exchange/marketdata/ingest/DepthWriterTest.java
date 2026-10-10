package com.exchange.marketdata.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.exchange.marketdata.candle.Market;
import com.exchange.marketdata.depth.BookSide;
import com.exchange.marketdata.depth.DepthBook;
import com.exchange.marketdata.depth.DepthLevel;
import com.exchange.marketdata.depth.DepthRepository;
import com.exchange.marketdata.ingest.DepthWriter.LevelDelta;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * {@link MongoTemplate} and {@link DepthRepository} are mocked -- what is under test is
 * {@link DepthWriter}'s own decisions: how a delta changes a level, which levels end up in
 * the update, what happens to a level that empties, and what survives a failed write.
 * Whether Mongo honours a {@code $unset} is Mongo's problem.
 */
@ExtendWith(MockitoExtension.class)
class DepthWriterTest {

    private static final String SYMBOL = "BTCUSDT";
    private static final String COLLECTION = "spot_market_depth";
    private static final long ONE = 100_000_000L;

    @Mock
    private MongoTemplate mongo;

    @Mock
    private DepthRepository books;

    private DepthWriter writer;
    private final List<LevelChangeSink.LevelChange> published = new ArrayList<>();

    @BeforeEach
    void setUp() {
        writer = new DepthWriter(mongo, books, published::addAll);
    }

    private static long price(long whole) {
        return whole * ONE;
    }

    private static LevelDelta delta(long sequence, BookSide side, long whole, long quantity) {
        return new LevelDelta(Market.SPOT, SYMBOL, sequence, side, price(whole), quantity);
    }

    @Test
    void anAcceptAddsWhatRestedAndATradeTakesItAway() {
        writer.apply(List.of(
                delta(1, BookSide.BID, 100, 3 * ONE),
                delta(2, BookSide.BID, 100, -ONE)));

        Document set = captureUpdate().get("$set", Document.class);
        assertThat(set.get("bids." + price(100))).isEqualTo(2 * ONE);
        assertThat(set.get("lastSequence")).isEqualTo(2L);
    }

    /**
     * A level with nothing left must be removed, not stored as zero. Nothing would ever
     * delete a stored zero, so the map would grow by one entry for every price the market
     * has ever touched and never shrink -- and every read would have to filter them out
     * to avoid rendering a price with no size behind it as liquidity.
     */
    @Test
    void aLevelThatEmptiesIsUnsetRatherThanStoredAsZero() {
        writer.apply(List.of(
                delta(1, BookSide.ASK, 101, 2 * ONE),
                delta(2, BookSide.ASK, 101, -2 * ONE)));

        Document update = captureUpdate();
        assertThat(update.get("$unset", Document.class)).containsKey("asks." + price(101));
        assertThat(update.get("$set", Document.class)).doesNotContainKey("asks." + price(101));
    }

    /** An amend to a new price is one event and two levels. The second leg shares the first's
     *  sequence and must not be mistaken for a redelivery -- but a real redelivery of both is. */
    @Test
    void twoLegsOfOneSequenceBothApply_andARedeliveryOfBothAppliesNeither() {
        List<LevelDelta> batch = List.of(
                delta(1, BookSide.BID, 100, 3 * ONE),
                delta(2, BookSide.BID, 100, -3 * ONE),
                delta(2, BookSide.BID, 99, 3 * ONE));
        writer.apply(batch);

        Document update = lastUpdate();
        assertThat(update.get("$unset", Document.class)).containsKey("bids." + price(100));
        assertThat(update.get("$set", Document.class).get("bids." + price(99))).isEqualTo(3 * ONE);

        writer.apply(batch);
        writer.apply(List.of(delta(3, BookSide.BID, 99, ONE)));
        assertThat(lastUpdate().get("$set", Document.class).get("bids." + price(99))).isEqualTo(4 * ONE);
    }

    /** The write is proportional to the trading, not to the size of the book: a batch that
     *  moves one level out of three must not resend the other two. */
    @Test
    void writesOnlyTheLevelsThatChanged() {
        writer.apply(List.of(
                delta(1, BookSide.BID, 100, ONE),
                delta(2, BookSide.BID, 99, ONE),
                delta(3, BookSide.BID, 98, ONE)));
        writer.apply(List.of(delta(4, BookSide.BID, 99, ONE)));

        Document set = lastUpdate().get("$set", Document.class);
        assertThat(set.get("bids." + price(99))).isEqualTo(2 * ONE);
        assertThat(set).doesNotContainKey("bids." + price(100));
        assertThat(set).doesNotContainKey("bids." + price(98));
    }

    @Test
    void topArraysAreBestPriceFirstOnBothSides() {
        writer.apply(List.of(
                delta(1, BookSide.BID, 99, ONE),
                delta(2, BookSide.BID, 101, 2 * ONE),
                delta(3, BookSide.BID, 100, 3 * ONE),
                delta(4, BookSide.ASK, 105, ONE),
                delta(5, BookSide.ASK, 103, 2 * ONE),
                delta(6, BookSide.ASK, 104, 3 * ONE)));

        Document set = captureUpdate().get("$set", Document.class);
        assertThat(set.get("topBids")).isEqualTo(List.of(
                new DepthLevel(price(101), 2 * ONE),
                new DepthLevel(price(100), 3 * ONE),
                new DepthLevel(price(99), ONE)));
        assertThat(set.get("topAsks")).isEqualTo(List.of(
                new DepthLevel(price(103), 2 * ONE),
                new DepthLevel(price(104), 3 * ONE),
                new DepthLevel(price(105), ONE)));
    }

    @Test
    void topArraysStopAtTopLevelsEvenWhenTheLadderIsDeeper() {
        List<LevelDelta> deep = new java.util.ArrayList<>();
        for (int i = 0; i < DepthBook.TOP_LEVELS + 10; i++) {
            deep.add(delta(i + 1, BookSide.BID, 100 - i, ONE));
        }

        writer.apply(deep);

        Document set = captureUpdate().get("$set", Document.class);
        @SuppressWarnings("unchecked")
        List<DepthLevel> topBids = (List<DepthLevel>) set.get("topBids");
        assertThat(topBids).hasSize(DepthBook.TOP_LEVELS);
        assertThat(topBids.get(0).price()).isEqualTo(price(100));
    }

    /** Depth is built from signed deltas, so applying one twice leaves the level wrong
     *  forever with no error anywhere. The whole batch is already applied here, so the
     *  retry must touch Mongo not at all. */
    @Test
    void redeliveryOfAnAlreadyAppliedBatchWritesNothing() {
        List<LevelDelta> batch = List.of(
                delta(1, BookSide.BID, 100, 3 * ONE),
                delta(2, BookSide.ASK, 101, 2 * ONE));

        writer.apply(batch);
        // The store holds the batch, as it does after that write succeeded.
        DepthBook stored = mock(DepthBook.class);
        when(stored.getLastSequence()).thenReturn(2L);
        when(books.findFull(Market.SPOT, SYMBOL)).thenReturn(Optional.of(stored));
        writer.apply(batch);

        verify(mongo, times(1))
                .upsert(any(Query.class), any(Update.class), eq(DepthBook.class), anyString());
    }

    /**
     * The failure the dirty set exists for, and the reason it is not just "what this batch
     * contained".
     *
     * <p>When the write fails the batch is not acknowledged, but the in-memory book has
     * already moved on -- so on redelivery every delta is skipped by the watermark. A
     * writer that decided what to persist from the deltas that applied would write nothing
     * at all on the retry, leaving Mongo permanently behind memory with nothing to notice.
     */
    @Test
    void aFailedWriteKeepsItsLevelsPendingForTheRetry() {
        when(mongo.upsert(any(Query.class), any(Update.class), eq(DepthBook.class), anyString()))
                .thenThrow(new IllegalStateException("mongo is down"))
                .thenReturn(null);

        List<LevelDelta> batch = List.of(delta(1, BookSide.BID, 100, 3 * ONE));

        assertThatThrownBy(() -> writer.apply(batch)).isInstanceOf(IllegalStateException.class);
        writer.apply(batch); // redelivered; every delta is below the watermark now

        // Two upserts, not one. Asserting only on the last captured update would pass
        // against a writer that never retried at all, since the failed attempt's update
        // is still the most recent one captured.
        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        verify(mongo, times(2))
                .upsert(any(Query.class), update.capture(), eq(DepthBook.class), eq(COLLECTION));

        Document retried = (Document) update.getValue().getUpdateObject();
        Document set = retried.get("$set", Document.class);
        assertThat(set.get("bids." + price(100))).isEqualTo(3 * ONE);
        assertThat(set.get("lastSequence")).isEqualTo(1L);
    }

    /**
     * A restart resumes the stored book rather than starting empty. Without this the first
     * delta after a restart would write a book containing one level, silently deleting
     * every other level from the top arrays that a client reads.
     */
    @Test
    void restoresTheStoredBookBeforeApplyingAnything() {
        DepthBook stored = mock(DepthBook.class);
        when(stored.getBids()).thenReturn(Map.of(String.valueOf(price(100)), 5 * ONE));
        when(stored.getAsks()).thenReturn(Map.of(String.valueOf(price(101)), 4 * ONE));
        when(stored.getLastSequence()).thenReturn(7L);
        when(books.findFull(Market.SPOT, SYMBOL)).thenReturn(Optional.of(stored));

        writer.apply(List.of(
                delta(7, BookSide.BID, 100, -5 * ONE),  // already applied; must be ignored
                delta(8, BookSide.BID, 100, ONE)));

        Document set = captureUpdate().get("$set", Document.class);
        assertThat(set.get("bids." + price(100))).isEqualTo(6 * ONE); // 5 restored + 1, not 1
        assertThat(set.get("topAsks")).isEqualTo(List.of(new DepthLevel(price(101), 4 * ONE)));
        assertThat(set.get("lastSequence")).isEqualTo(8L);
    }

    @Test
    void guardsTheWriteWithTheWatermarkCondition() {
        writer.apply(List.of(delta(5, BookSide.BID, 100, ONE)));

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(mongo).upsert(query.capture(), any(Update.class), eq(DepthBook.class), anyString());
        assertThat(query.getValue().getQueryObject().toJson())
                .contains("\"lastSequence\"")
                .contains("$lt")
                .contains("$or");
    }

    @Test
    void writesNothingForAnEmptyBatch() {
        writer.apply(List.of());

        verify(mongo, never())
                .upsert(any(Query.class), any(Update.class), eq(DepthBook.class), anyString());
        verify(books, never()).findFull(any(), anyString());
    }

    /** Memory outlives the store: after a wipe, the same sequences arrive again and must be
     *  applied, not skipped as redeliveries -- or the book is frozen with no error. */
    @Test
    void aBookTheStoreNoLongerHoldsIsRebuiltNotSkipped() {
        writer.apply(List.of(delta(5, BookSide.BID, 100, 3 * ONE)));
        when(books.findFull(Market.SPOT, SYMBOL)).thenReturn(Optional.empty());

        writer.apply(List.of(delta(1, BookSide.ASK, 101, 2 * ONE)));

        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        verify(mongo, times(2)).upsert(any(Query.class), update.capture(), eq(DepthBook.class), eq(COLLECTION));
        Document second = (Document) update.getAllValues().get(1).getUpdateObject();
        assertThat(second.get("$unset", Document.class)).containsKey("bids." + price(100));
        assertThat(second.get("$set", Document.class).get("asks." + price(101))).isEqualTo(2 * ONE);
        assertThat(second.get("$set", Document.class).get("lastSequence")).isEqualTo(1L);
    }

    /** One store read per watermark, not one per skipped event. */
    @Test
    void theStoreIsAskedOncePerWatermark() {
        writer.apply(List.of(delta(3, BookSide.BID, 100, ONE)));
        DepthBook stored = mock(DepthBook.class);
        when(stored.getLastSequence()).thenReturn(3L);
        when(books.findFull(Market.SPOT, SYMBOL)).thenReturn(Optional.of(stored));

        writer.apply(List.of(delta(1, BookSide.BID, 100, ONE), delta(2, BookSide.BID, 100, ONE), delta(3, BookSide.BID, 100, ONE)));

        // One first-touch load, one check for the three skipped events -- not one per event.
        verify(books, times(2)).findFull(Market.SPOT, SYMBOL);
        verify(mongo, times(1)).upsert(any(Query.class), any(Update.class), eq(DepthBook.class), anyString());
    }

    private Document captureUpdate() {
        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        verify(mongo).upsert(any(Query.class), update.capture(), eq(DepthBook.class),
                eq(COLLECTION));
        return (Document) update.getValue().getUpdateObject();
    }

    private Document lastUpdate() {
        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        verify(mongo, org.mockito.Mockito.atLeastOnce())
                .upsert(any(Query.class), update.capture(), eq(DepthBook.class), eq(COLLECTION));
        return (Document) update.getValue().getUpdateObject();
    }

    /**
     * The feed carries each level's size AFTER the event, and only once the book is written:
     * a client that applies these has the same ladder the store holds, at the same sequence.
     */
    @Test
    void publishesEachLevelsNewSizeOnceTheBookIsWritten() {
        writer.apply(List.of(
                new LevelDelta(Market.SPOT, "BTCUSDT", 1, BookSide.BID, price(100), ONE),
                new LevelDelta(Market.SPOT, "BTCUSDT", 2, BookSide.BID, price(100), ONE),
                new LevelDelta(Market.SPOT, "BTCUSDT", 3, BookSide.BID, price(100), -2 * ONE)));

        assertThat(published).extracting(LevelChangeSink.LevelChange::sequence).containsExactly(1L, 2L, 3L);
        assertThat(published).extracting(LevelChangeSink.LevelChange::quantity).containsExactly(ONE, 2 * ONE, 0L);
        assertThat(published).allSatisfy(c -> assertThat(c.side()).isEqualTo(BookSide.BID));
    }

    /** A failed write publishes nothing; the retry that lands the write publishes the changes. */
    @Test
    void aFailedWriteHoldsTheChangesBackUntilTheRetryLands() {
        when(mongo.upsert(any(Query.class), any(Update.class), eq(DepthBook.class), anyString()))
                .thenThrow(new RuntimeException("mongo down"))
                .thenReturn(null);
        List<LevelDelta> batch = List.of(new LevelDelta(Market.SPOT, "BTCUSDT", 1, BookSide.ASK, price(101), ONE));
        assertThatThrownBy(() -> writer.apply(batch)).isInstanceOf(RuntimeException.class);
        assertThat(published).isEmpty();
        writer.apply(batch); // the redelivery: folds skip, the write lands, the change goes out
        assertThat(published).hasSize(1);
    }
}
