package com.exchange.marketdata.ingest;

import com.exchange.marketdata.candle.Market;
import com.exchange.marketdata.tape.PublicTrade;
import com.exchange.marketdata.tape.TradeTape;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * Appends fills to each symbol's public trade tape.
 *
 * <p>The simplest of this app's three writers, because a trade is already the thing being
 * stored -- there is no folding, no arithmetic, nothing derived. What it does do is decide
 * what a stranger may see: {@link PublicTrade} carries no user ids and no fees, and this is
 * the only place that choice is made.
 *
 * <p><b>Bounded in the write, not by a cleanup job.</b> One {@code $push} with
 * {@code $slice} appends the batch and drops the oldest overflow in the same atomic update,
 * so the document can never grow past {@link TradeTape#CAPACITY} and nothing has to come
 * along later to trim it. Dropped trades are not lost -- {@code spot_trades} in MySQL keeps
 * every fill forever.
 *
 * <p><b>Stateless, unlike {@code DepthWriter}.</b> Depth holds a book in memory because it
 * must know a level's current quantity to write an absolute value; a tape only ever appends
 * what it was handed, so the watermark in the update is the whole of its safety. That also
 * means this writer imposes no single-instance requirement of its own.
 *
 * <p>Idempotency is the same guard the others use, and needed for the same reason: appending
 * is not idempotent, so a batch redelivered under Kafka's at-least-once delivery would print
 * every trade in it twice -- with no error, just a tape that disagrees with the chart.
 */
@Component
public class TradeTapeWriter {

    private final MongoTemplate mongo;

    public TradeTapeWriter(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    /** One fill, already decoded off the wire and stripped of everything private. */
    public record TapeEntry(Market market, String symbol, PublicTrade trade) {
    }

    /**
     * Appends every entry that has not already been appended. Safe to call twice with the
     * same input: the second call finds each tape's watermark already at or past these
     * sequences and writes nothing.
     */
    public void apply(List<TapeEntry> entries) {
        if (entries.isEmpty()) {
            return;
        }

        // One update per symbol, not per trade. A batch of two thousand fills across a
        // dozen markets is a dozen writes, and the $push carries them all.
        Map<String, List<TapeEntry>> bySymbol = new LinkedHashMap<>();
        for (TapeEntry entry : entries) {
            bySymbol.computeIfAbsent(TradeTape.idOf(entry.market(), entry.symbol()),
                    id -> new ArrayList<>()).add(entry);
        }
        bySymbol.values().forEach(this::write);
    }

    private void write(List<TapeEntry> forSymbol) {
        TapeEntry first = forSymbol.get(0);
        List<PublicTrade> trades = forSymbol.stream().map(TapeEntry::trade).toList();
        // The batch arrives in partition order, which is sequence order for one symbol, so
        // the highest is simply the last.
        long highestSequence = trades.get(trades.size() - 1).sequence();

        Query guard = new Query(Criteria.where("_id")
                .is(TradeTape.idOf(first.market(), first.symbol()))
                .orOperator(Criteria.where("lastSequence").lt(highestSequence),
                        Criteria.where("lastSequence").exists(false)));

        Update update = new Update()
                .setOnInsert("market", first.market())
                .setOnInsert("symbol", first.symbol())
                .set("lastSequence", highestSequence)
                .set("updatedAt", Instant.now());
        // $slice keeps the LAST capacity entries -- a negative slice, because the array is
        // ordered oldest first and it is the newest that must survive.
        update.push("trades").slice(-TradeTape.CAPACITY).each(trades.toArray());

        try {
            mongo.upsert(guard, update, TradeTape.class, first.market().tapeCollection());
        } catch (DuplicateKeyException alreadyPast) {
            // The guard matched nothing because the stored watermark is already at or past this
            // batch, so the upsert tried to insert a second document. That is a redelivery of
            // work that was done -- unless the batch starts the market over at sequence 1, in
            // which case the stored tape is the old history and this batch replaces it.
            if (trades.get(0).sequence() != 1) {
                return;
            }
            Update replace = new Update()
                    .setOnInsert("market", first.market())
                    .setOnInsert("symbol", first.symbol())
                    .set("trades", trades)
                    .set("lastSequence", highestSequence)
                    .set("updatedAt", Instant.now());
            mongo.upsert(new Query(Criteria.where("_id").is(TradeTape.idOf(first.market(), first.symbol()))),
                    replace, TradeTape.class, first.market().tapeCollection());
        }
    }
}
