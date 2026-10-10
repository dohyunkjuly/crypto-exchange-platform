package com.exchange.spot.matching.engine;

import com.exchange.spot.matching.book.OrderBook;
import com.exchange.spot.matching.trigger.TriggerBook;
import com.google.protobuf.ByteString;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * Everything the engine holds for one market. Created on the first command for a symbol,
 * which is why listing a new market needs no coordination — a row in {@code spot_symbols},
 * and the first order creates the book.
 *
 * <p>All of this is derived from the command log. Replay it and every field lands back on
 * the same value, which is what makes recovery-by-replay valid.
 */
final class SymbolState {

    /**
     * How many command ids a market remembers for deduplication. A bound, because the
     * command log is kept forever and this must not grow with it. A count rather than an
     * age, because the engine has no clock: a count is a function of the log alone, so a
     * replay evicts exactly what the original run evicted.
     *
     * <p>About 100 bytes an entry once the set's own nodes are counted, so 1 MB a market at
     * this size, and 160 KB more in every snapshot. The window it buys is that many of the
     * market's own commands: minutes on a typical market, seconds on one running at the
     * partition ceiling in docs/reference/scalability.md. A repeat arriving later than that
     * is not a retry -- see docs/reference/delivery-guarantees.md for what is and is not
     * covered.
     */
    static final int MAX_REMEMBERED_COMMANDS = 10_000;

    private final String symbol;

    /**
     * Which command partition this market arrives on: its {@code spot_symbols.command_partition}.
     * Fixed for the life of the symbol -- see "Which partition a market uses" in
     * docs/reference/matching-engine.md.
     *
     * <p>A snapshot describes one partition, so it must contain only the symbols that
     * belong to it -- otherwise every partition's snapshot would hold every symbol, and
     * restoring the second one would double the first.
     */
    private final int partition;

    private final OrderBook book;

    /**
     * Conditional orders, beside the book rather than on it. They are not matchable, so
     * putting them on the book would advertise liquidity that does not exist.
     */
    private final TriggerBook triggers = new TriggerBook();

    /**
     * A stable 64-bit hash of the symbol, computed once. Used to make trade ids unique
     * across markets without hashing on every fill.
     */
    private final long symbolHash;

    /** Per-symbol, monotonic, dense. Assigned to every event this market produces. */
    private long sequence;

    /**
     * The price of the most recent fill. Zero until this market has traded — which is why
     * a conditional order arriving before the first trade is rejected rather than parked;
     * there is nothing to evaluate its trigger against.
     */
    private long lastTradePrice;

    /**
     * The ids of the last {@link #MAX_REMEMBERED_COMMANDS} commands, oldest first. Insertion
     * order is what makes "oldest" well defined, and what lets a snapshot carry the set out
     * and back in the same order, so eviction continues where it left off.
     */
    private final LinkedHashSet<ByteString> recentCommandIds = new LinkedHashSet<>();

    SymbolState(String symbol, int partition) {
        this.symbol = symbol;
        this.partition = partition;
        this.book = new OrderBook(symbol);
        this.symbolHash = hash64(symbol);
    }

    String symbol() {
        return symbol;
    }

    int partition() {
        return partition;
    }

    OrderBook book() {
        return book;
    }

    TriggerBook triggers() {
        return triggers;
    }

    long lastTradePrice() {
        return lastTradePrice;
    }

    boolean hasTraded() {
        return lastTradePrice > 0;
    }

    void recordTrade(long price) {
        this.lastTradePrice = price;
    }

    long nextSequence() {
        return ++sequence;
    }

    long currentSequence() {
        return sequence;
    }

    /**
     * Records that this command is being processed, and says whether it is the first time.
     * False means a repeat: the caller must do nothing, because the first copy already did.
     *
     * <p>An id-less command is never a repeat. It cannot be told apart from any other
     * id-less command, so deduplicating it would turn one producer's bug into a market
     * that silently swallows every command after the first.
     */
    boolean firstSightOf(ByteString commandId) {
        if (commandId.isEmpty()) {
            return true;
        }
        if (!recentCommandIds.add(commandId)) {
            return false;
        }
        if (recentCommandIds.size() > MAX_REMEMBERED_COMMANDS) {
            recentCommandIds.remove(recentCommandIds.iterator().next());
        }
        return true;
    }

    /** For a snapshot: oldest first, the order {@link #restoreCounters} expects back. */
    List<ByteString> recentCommandIds() {
        return new ArrayList<>(recentCommandIds);
    }

    /**
     * Restores the counters a snapshot carried.
     *
     * <p>The sequence matters as much as the book: resume at 1 and every consumer sees the
     * numbers it has already processed, concludes it lost a message, and resyncs — or
     * worse, applies them twice.
     */
    void restoreCounters(long sequence, long lastTradePrice,
                         Collection<ByteString> recentCommandIds) {
        this.sequence = sequence;
        this.lastTradePrice = lastTradePrice;
        this.recentCommandIds.clear();
        this.recentCommandIds.addAll(recentCommandIds);
    }

    /**
     * A trade id that is time-ordered, globally unique, and — critically —
     * <b>deterministic</b>.
     *
     * <p>{@code UUID.randomUUID()} would be the obvious choice and would silently destroy
     * replay: rebuilding the book from the log would mint different trade ids than the
     * original run, so nothing downstream could reconcile.
     *
     * <p>Every input here comes from the log. High bits are the timestamp, so ids sort by
     * creation time and the BINARY(16) primary key does not fragment on insert. Low bits
     * carry the symbol hash and the sequence, which is unique per market.
     */
    UUID tradeId(long timestampUs) {
        long timestampMs = timestampUs / 1_000L;
        long msb = (timestampMs << 16) | ((symbolHash >>> 48) & 0xFFFFL);
        long lsb = (symbolHash & 0xFFFFFFFF00000000L) | (sequence & 0xFFFFFFFFL);
        return new UUID(msb, lsb);
    }

    private static long hash64(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(digest).getLong();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
    }
}
