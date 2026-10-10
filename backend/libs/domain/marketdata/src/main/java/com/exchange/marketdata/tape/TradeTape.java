package com.exchange.marketdata.tape;

import com.exchange.marketdata.candle.Market;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One symbol's recent trades: the tape a trading screen prints down its side.
 *
 * <p>A projection over the newest fills; {@code spot_trades} keeps every one of them forever.
 *
 * <p><b>Bounded, which is not the same as expiring.</b> At most {@link #CAPACITY} trades --
 * {@code $slice} drops the oldest in the same atomic update that appends. Nothing is lost,
 * which is why this can be capped while candles deliberately are not: a tape is a view of the
 * last few minutes, a candle is the only copy of its own history.
 *
 * <p>One document per symbol, not per trade, for the same reason -- a per-trade collection
 * grows without limit and then needs a deletion policy. It also makes a read one document
 * rather than a sorted range query.
 *
 * <p>Stored oldest first, the order {@code $push} appends and the engine produced;
 * {@link TradeTapeService} reverses for reading.
 *
 * <p>{@code lastSequence} is the idempotency watermark -- appending is not idempotent, so a
 * redelivered batch would print every trade twice, with no error.
 */
// No collection name here on purpose, as with Candle and DepthBook: there is one collection
// per product (Market#tapeCollection), so every read and write passes the name explicitly.
@Document
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TradeTape {

    /**
     * How many trades a symbol keeps.
     *
     * <p>Comfortably more than a screen shows, so scrolling the panel needs no second
     * request, and small enough that the document stays a few tens of kilobytes. Anyone
     * wanting more than this wants {@code spot_trades}, not a tape.
     */
    public static final int CAPACITY = 200;

    /** {@code market:SYMBOL} -- deterministic, so the writer upserts to a known id. */
    @Id
    private String id;

    private Market market;
    private String symbol;

    /** Oldest first. Capped at {@link #CAPACITY} by the writer's own update. */
    private List<PublicTrade> trades;

    private long lastSequence;
    private Instant updatedAt;

    public static String idOf(Market market, String symbol) {
        return market.code() + ":" + symbol;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TradeTape other)) {
            return false;
        }
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getClass());
    }
}
