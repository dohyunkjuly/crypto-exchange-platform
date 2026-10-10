package com.exchange.marketdata.stream;

import com.exchange.common.money.Money;
import com.exchange.marketdata.candle.Market;
import com.exchange.marketdata.depth.Depth;
import com.exchange.marketdata.depth.DepthBook;
import com.exchange.marketdata.depth.DepthGroupings;
import com.exchange.marketdata.depth.DepthService;
import com.exchange.marketdata.stream.exception.InvalidGroupingException;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * {@code depth} -- the visible top of the order book.
 *
 * <p>Builds the frames; {@link DepthPublisher} decides when to push them. The split is not
 * cosmetic: {@link MarketWsHandler} collects every {@link WsChannel} at construction, so a
 * channel that also held a reference back to the handler would be a constructor cycle
 * Spring refuses to start. Frame-building has no such dependency, so it lives here and the
 * pushing lives there.
 *
 * <p>Unlike {@code trade}, this channel carries state, so a new subscriber is sent the book
 * immediately rather than waiting for it to move -- see {@link #initialFrame}. That is also
 * what makes this stream self-sufficient: a client needs no REST snapshot and no sequence
 * reconciliation to draw a correct ladder, because every frame is complete in itself.
 */
@Component
public class DepthChannel implements WsChannel {

    public static final String NAME = "depth";

    /**
     * How deep a pushed frame goes: the whole precomputed top, and no more. {@code DepthService}
     * switches storage paths at {@code TOP_LEVELS}: within it, a frame is served from the
     * precomputed arrays with a projected read; past it, every tick pulls the entire stored
     * ladder and sorts it, for every watched symbol, ten times a second. Not configurable, so
     * nothing can quietly cross that line: going deeper means raising {@code TOP_LEVELS}, so the
     * writer precomputes that many. Bandwidth is the other half -- this is multiplied by every
     * subscriber on every tick -- which is why the web's ladder shows exactly this many.
     */
    private static final int LEVELS = DepthBook.TOP_LEVELS;

    private final DepthService depth;
    private final DepthGroupings catalog;

    public DepthChannel(DepthService depth, DepthGroupings catalog) {
        this.depth = depth;
        this.catalog = catalog;
    }

    @Override
    public String name() {
        return NAME;
    }

    /**
     * A grouping must be one this market actually offers.
     *
     * <p>Not merely "a positive number": a grouping that is not a whole multiple of the
     * tick puts bucket boundaries mid-tick, so the same price lands in different buckets
     * depending on where the ladder starts, and one finer than the tick makes rows nothing
     * can ever fill. Both render a subtly wrong book rather than failing, which is why this
     * is checked rather than trusted.
     *
     * <p>Absent is always fine and means the raw ladder -- the default, and the cheap path.
     * A symbol this instance has no listing for is accepted with any positive grouping: the
     * catalog exists to populate a selector, not to police which markets may be watched,
     * and refusing here would make an unlisted-but-trading market invisible.
     */
    @Override
    public void validate(Subscription subscription) {
        if (subscription.grouping().isEmpty()) {
            return;
        }
        long grouping = parse(subscription.grouping());
        catalog.groupingsOf(subscription.symbol()).ifPresent(offered -> {
            if (!offered.contains(grouping)) {
                throw new InvalidGroupingException("Unsupported grouping for " + subscription.symbol() + ": "
                        + subscription.grouping());
            }
        });
    }

    /**
     * The decimal a human chose from the selector, as the 1e8-scaled long everything below
     * this works in.
     *
     * <p>Through {@code Money.toWire}, which rejects more precision than the scale can hold
     * rather than rounding it away -- so {@code "0.000000001"} is an error, not a silent
     * zero, and a zero grouping would divide the ladder into buckets of nothing.
     */
    static long parse(String grouping) {
        try {
            long scaled = Money.toWire(grouping);
            if (scaled <= 0) {
                throw new IllegalArgumentException("grouping must be positive");
            }
            return scaled;
        } catch (ArithmeticException | IllegalArgumentException e) {
            throw new InvalidGroupingException("Unreadable grouping: " + grouping);
        }
    }

    /** Always present: a book with nothing in it is still an answer, and a client that got
     *  no opening frame could not tell "empty market" from "still loading". */
    @Override
    public Optional<WsMessage<?>> initialFrame(Subscription subscription) {
        return Optional.of(current(subscription)
                .map(DepthChannel::frameOf)
                .orElseGet(() -> WsMessage.push(NAME, DepthData.empty(subscription.symbol()))));
    }

    static WsMessage<DepthData> frameOf(Depth book) {
        return WsMessage.push(NAME, DepthData.of(book));
    }

    /** One subscription's view of the book. Ungrouped reads the precomputed top; grouped
     *  reads the whole ladder, because a bucket is a price range and the top of book cannot
     *  answer a range question. */
    Optional<Depth> current(Subscription subscription) {
        return subscription.grouping().isEmpty()
                ? depth.of(Market.SPOT, subscription.symbol(), LEVELS)
                : depth.grouped(Market.SPOT, subscription.symbol(),
                        parse(subscription.grouping()), LEVELS);
    }

    /**
     * Every view of one symbol its subscribers asked for, from a single read -- what the
     * publisher uses.
     *
     * <p>One fetch however many bucket sizes are in play, and the ungrouped ladder comes
     * out of the same document rather than a second query, so every frame this tick
     * describes one book at one sequence.
     */
    Optional<DepthService.Views> currentViews(java.util.Collection<Long> groupings,
                                              boolean includeUngrouped, String symbol) {
        return depth.views(Market.SPOT, symbol, groupings, includeUngrouped, LEVELS);
    }
}
