package com.exchange.marketdata.ingest;

import com.exchange.marketdata.candle.Market;
import com.exchange.marketdata.depth.BookSide;
import com.exchange.marketdata.depth.DepthBook;
import com.exchange.marketdata.depth.DepthLevel;
import com.exchange.marketdata.depth.DepthRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * Folds order events into each symbol's L2 book.
 *
 * <p>The engine never emits a book -- {@code events.proto} says so outright -- so depth is
 * this: {@code OrderAccepted} adds what rested, {@code TradeExecuted} removes what filled,
 * {@code OrderCanceled} removes what was withdrawn. Every one of those is an explicit
 * delta on the wire; nothing here has to infer anything.
 *
 * <p><b>Why the book is held in memory.</b> A purely stateless writer is possible -- one
 * {@code $inc} per level, guarded by the sequence watermark -- and it was the first design.
 * It was rejected for two reasons that only show up later: nothing can precompute {@link
 * DepthBook#getTopBids()} without being able to see the whole ladder sorted, and a level
 * that empties has to be left stored as zero, so the maps accumulate an entry for every
 * price the market has ever touched and never shrink. Holding the book costs about a
 * megabyte per busy symbol and makes both problems disappear: quantities are written
 * absolute, so the write is idempotent by construction rather than by arithmetic.
 *
 * <p><b>This requires a single market-data instance.</b> Already the case -- the listener's
 * concurrency equals the topic's partition count, so a second process would sit idle -- but
 * it is now load-bearing rather than incidental. Two processes folding the same partition
 * would each hold a book that is right about its own events and wrong about the other's.
 * The guard on {@link #write} is what turns that into a loud failure instead of a quiet
 * one; see its comment.
 *
 * <p>Within this process, concurrency is safe: a symbol's events always land on one
 * partition, and Kafka gives a partition to exactly one listener thread. The per-book lock
 * covers the brief window during a rebalance when a partition can move between threads.
 */
@Component
public class DepthWriter {

    private static final Logger log = LoggerFactory.getLogger(DepthWriter.class);

    private final MongoTemplate mongo;
    private final DepthRepository books;
    private final LevelChangeSink changes;

    /** {@code market:SYMBOL} -&gt; that symbol's book. Populated lazily, because which
     *  symbols this process owns is not known until Kafka assigns partitions. */
    private final Map<String, SymbolBook> inMemory = new ConcurrentHashMap<>();

    public DepthWriter(MongoTemplate mongo, DepthRepository books, LevelChangeSink changes) {
        this.mongo = mongo;
        this.books = books;
        this.changes = changes;
    }

    /**
     * One level change, already decoded off the wire.
     *
     * @param quantityDelta signed and 1e8-scaled: positive when an order rested, negative
     *                      when it filled or was withdrawn.
     */
    private static final long NONE = -1;

    public record LevelDelta(Market market, String symbol, long sequence,
                             BookSide side, long price, long quantityDelta) {
    }

    /**
     * Applies every delta that has not already been applied, then persists whatever
     * changed. Safe to call twice with the same input: the second call finds each book's
     * watermark already past these sequences and writes nothing.
     */
    public void apply(List<LevelDelta> deltas) {
        if (deltas.isEmpty()) {
            return;
        }

        // Insertion-ordered so a failure writes the same books in the same order every
        // time, which makes a partial failure reproducible rather than a different subset
        // each attempt.
        Set<SymbolBook> involved = new LinkedHashSet<>();
        for (LevelDelta delta : deltas) {
            SymbolBook book = bookFor(delta.market(), delta.symbol());
            fold(book, delta);
            involved.add(book);
        }
        involved.forEach(book -> book.foldingSequence = NONE);

        for (SymbolBook book : involved) {
            write(book);
        }
    }

    /**
     * Every book this batch touched is written if it is dirty -- <b>not</b> only the books
     * whose deltas applied.
     *
     * <p>That distinction is the whole reason dirty levels are tracked separately from the
     * batch. When a write fails the batch is not acknowledged, but the in-memory book has
     * already moved on, so on redelivery every delta is skipped by the watermark. Keying
     * the write off "did anything apply" would mean the retry writes nothing at all and the
     * pending levels are lost silently -- the book in Mongo permanently behind the one in
     * memory, with no error anywhere.
     */
    private void write(SymbolBook book) {
        synchronized (book) {
            if (book.dirtyBids.isEmpty() && book.dirtyAsks.isEmpty()) {
                return;
            }
            mongo.upsert(guard(book), book.pendingUpdate(), DepthBook.class,
                    book.market.depthCollection());
            book.dirtyBids.clear();
            book.dirtyAsks.clear();
            book.storeWatermark = -1; // the store may change under us; ask again on the next skip candidate
            // Only now, with the store holding them: a change on the feed the store lacks would let
            // a client resync from a snapshot that is behind it. Kept pending across a failed write
            // for the same reason the dirty levels are.
            if (!book.pendingChanges.isEmpty()) {
                changes.publish(List.copyOf(book.pendingChanges));
                book.pendingChanges.clear();
            }
        }
    }

    /**
     * Matches this book only while the stored watermark is behind the one being written.
     *
     * <p>Redelivery is already handled in memory, so in a correctly deployed system this
     * condition always holds and the guard never does anything. It is here for the one case
     * memory cannot see: a second writer. If that happens the query matches nothing, the
     * upsert tries to insert a document whose {@code _id} already exists, and the resulting
     * duplicate-key error stops the batch. That is the intended outcome -- two writers
     * silently interleaving absolute quantities would leave a book that is wrong and looks
     * fine, and a stuck consumer is far easier to notice than that.
     */
    private static Query guard(SymbolBook book) {
        return new Query(Criteria.where("_id").is(DepthBook.idOf(book.market, book.symbol))
                .orOperator(Criteria.where("lastSequence").lt(book.lastSequence),
                        Criteria.where("lastSequence").exists(false)));
    }

    /**
     * Whether a sequence at or below the in-memory watermark is really a redelivery.
     *
     * <p>Memory outlives the store: a process that has run for days keeps its books through a
     * wiped Mongo or a reset engine, and would then skip every event of a market's new history
     * as "already applied" -- a book frozen forever, with no error. So before skipping, the
     * store is asked once per watermark: if it holds this sequence, this is a redelivery; if it
     * does not, memory is stale and the book is rebuilt from what the store has (or nothing).
     */
    private boolean staleMemory(SymbolBook book, long sequence) {
        if (sequence <= book.storeWatermark) {
            return false;
        }
        Optional<DepthBook> stored = books.findFull(book.market, book.symbol);
        book.storeWatermark = stored.map(DepthBook::getLastSequence).orElse(0L);
        if (sequence <= book.storeWatermark) {
            return false;
        }
        log.warn("{} book in memory (sequence {}) is ahead of the store ({}); rebuilding from the store",
                book.symbol, book.lastSequence, book.storeWatermark);
        book.reset();
        stored.ifPresent(book::restore);
        return true;
    }

    /** Loads from Mongo on first touch, so a restart resumes the book it left rather than
     *  rebuilding it from an event stream that no longer reaches back far enough. The read
     *  runs inside {@code computeIfAbsent} deliberately: it happens once per symbol per
     *  process, and doing it there is what stops two threads racing to load the same one. */
    private SymbolBook bookFor(Market market, String symbol) {
        return inMemory.computeIfAbsent(DepthBook.idOf(market, symbol), id -> {
            SymbolBook book = new SymbolBook(market, symbol);
            books.findFull(market, symbol).ifPresent(book::restore);
            return book;
        });
    }

    private void fold(SymbolBook book, LevelDelta delta) {
        synchronized (book) {
            // One event can move two levels: an amend to a new price leaves one and joins
            // another. Its second leg shares the sequence the first just set, and is not a
            // redelivery -- a real one finds the first leg already skipped.
            boolean nextLeg = delta.sequence() == book.foldingSequence;
            if (!nextLeg && delta.sequence() <= book.lastSequence
                    && !staleMemory(book, delta.sequence())) {
                return; // already applied -- a redelivery
            }
            book.foldingSequence = delta.sequence();
            NavigableMap<Long, Long> levels = book.levels(delta.side());
            long updated = levels.getOrDefault(delta.price(), 0L) + delta.quantityDelta();
            if (updated > 0) {
                levels.put(delta.price(), updated);
            } else {
                // Gone, not zero: a stored zero would never be removed by anything, and the
                // maps would grow by one entry per price ever touched. A negative would mean
                // the fold is broken, and keeping it would spread that error to every later
                // delta at this price.
                levels.remove(delta.price());
            }
            book.dirty(delta.side()).add(delta.price());
            book.lastSequence = delta.sequence();
            book.pendingChanges.add(new LevelChangeSink.LevelChange(book.market, book.symbol, delta.sequence(),
                    delta.side(), delta.price(), Math.max(updated, 0)));
        }
    }

    /** One symbol's book, plus which levels have changed since the last successful write. */
    private static final class SymbolBook {

        private final Market market;
        private final String symbol;

        /** Reverse-ordered, so iteration yields the best bid first and the top of book is
         *  the first N entries rather than a sort. */
        private final NavigableMap<Long, Long> bids = new TreeMap<>(Comparator.reverseOrder());
        private final NavigableMap<Long, Long> asks = new TreeMap<>();

        private final Set<Long> dirtyBids = new LinkedHashSet<>();
        private final Set<Long> dirtyAsks = new LinkedHashSet<>();
        /** Each level's size after each applied event, in order, until the write holding them lands. */
        private final List<LevelChangeSink.LevelChange> pendingChanges = new ArrayList<>();

        private long lastSequence;
        /** The sequence whose legs are being folded right now; NONE between batches. */
        private long foldingSequence = NONE;
        /** What the store held when last asked; -1 until asked, and again after every write. See staleMemory. */
        private long storeWatermark = -1;

        private SymbolBook(Market market, String symbol) {
            this.market = market;
            this.symbol = symbol;
        }

        private NavigableMap<Long, Long> levels(BookSide side) {
            return side == BookSide.BID ? bids : asks;
        }

        private Set<Long> dirty(BookSide side) {
            return side == BookSide.BID ? dirtyBids : dirtyAsks;
        }

        /** Forgets what memory had: every level is marked dirty so the next write states it, and
         *  changes pending from the discarded state go with it -- the refold reports them again. */
        private void reset() {
            dirtyBids.addAll(bids.keySet());
            dirtyAsks.addAll(asks.keySet());
            bids.clear();
            asks.clear();
            pendingChanges.clear();
            lastSequence = 0;
        }

        private void restore(DepthBook stored) {
            restoreSide(stored.getBids(), bids);
            restoreSide(stored.getAsks(), asks);
            lastSequence = stored.getLastSequence();
        }

        private static void restoreSide(Map<String, Long> stored, NavigableMap<Long, Long> into) {
            if (stored == null) {
                return;
            }
            stored.forEach((price, quantity) -> into.put(Long.parseLong(price), quantity));
        }

        /**
         * Only the levels that changed, plus both precomputed arrays and the watermark.
         *
         * <p>Sending the changed levels rather than the whole ladder is what keeps the
         * write proportional to the trading, not to the size of the book -- a busy batch
         * touches tens of levels out of thousands. Quantities are absolute rather than
         * increments, so applying this update twice lands on the same numbers.
         */
        private Update pendingUpdate() {
            Update update = new Update()
                    .setOnInsert("market", market)
                    .setOnInsert("symbol", symbol);

            stage(update, "bids", bids, dirtyBids);
            stage(update, "asks", asks, dirtyAsks);

            return update
                    .set("topBids", top(bids))
                    .set("topAsks", top(asks))
                    .set("lastSequence", lastSequence)
                    .set("updatedAt", Instant.now());
        }

        private static void stage(Update update, String field,
                                  NavigableMap<Long, Long> levels, Set<Long> dirty) {
            for (Long price : dirty) {
                Long quantity = levels.get(price);
                if (quantity == null) {
                    update.unset(field + "." + price);
                } else {
                    update.set(field + "." + price, quantity);
                }
            }
        }

        /** The first {@link DepthBook#TOP_LEVELS} entries -- already in book order, since
         *  each map is ordered best-price-first. */
        private static List<DepthLevel> top(NavigableMap<Long, Long> levels) {
            List<DepthLevel> best = new ArrayList<>(Math.min(levels.size(), DepthBook.TOP_LEVELS));
            for (Map.Entry<Long, Long> level : levels.entrySet()) {
                if (best.size() == DepthBook.TOP_LEVELS) {
                    break;
                }
                best.add(new DepthLevel(level.getKey(), level.getValue()));
            }
            return best;
        }
    }
}
