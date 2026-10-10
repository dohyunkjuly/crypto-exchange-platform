package com.exchange.marketdata.stream;

import com.exchange.marketdata.depth.Depth;
import com.exchange.marketdata.depth.DepthService;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Pushes the order book to everyone watching it, ten times a second.
 *
 * <p><b>Polls MongoDB rather than folding Kafka.</b> Several instances each folding the stream
 * would each hold their own book -- books that can disagree, with a user seeing different depth
 * depending on which instance they reconnected to. Reading the one {@code market-data}
 * maintains keeps every instance identical and stateless.
 *
 * <p><b>Reads do not scale with viewers or with groupings.</b> A symbol is fetched once per
 * tick however many people watch it and however many bucket sizes they chose: grouping the same
 * ladder four ways is four walks over a list already in memory, where fetching it four times is
 * four reads of the largest document here. Only fan-out grows, and
 * {@link MarketWsHandler#broadcast} serialises once per frame.
 *
 * <p>An ungrouped subscription never triggers that read -- it is served from the precomputed
 * top of book, so a page that never touches the grouping selector costs what it always did.
 *
 * <p><b>An unchanged book sends nothing.</b> Frames are complete refreshes, so resending an
 * identical one is waste, and most symbols are quiet. The check is {@code lastSequence}, which
 * moves if and only if an event was applied. New subscribers do not depend on it:
 * {@link DepthChannel#initialFrame} hands them the book at subscribe time.
 *
 * <p>So the first subscriber to a symbol sees it twice -- the opening frame, then this
 * publisher's first tick, which has no watermark yet. Left alone: suppressing it would mean the
 * handler reporting every subscription to this class, the dependency the
 * {@link DepthChannel}/{@code DepthPublisher} split exists to avoid, and a duplicate full
 * refresh is idempotent by definition.
 */
@Component
public class DepthPublisher {

    private final DepthChannel channel;
    private final MarketFanout sockets;

    /** Symbol -&gt; the sequence last broadcast from this instance. Per symbol rather than per
     *  subscription: every grouping of one book moves together, because they are the same
     *  book. Per instance, because it describes what these sockets have been sent. */
    private final Map<String, Long> published = new ConcurrentHashMap<>();

    public DepthPublisher(DepthChannel channel, MarketFanout sockets) {
        this.channel = channel;
        this.sockets = sockets;
    }

    /**
     * {@code fixedDelay}, not {@code fixedRate}: if a tick ever overruns its interval,
     * spacing the next one from the finish keeps ticks from stacking up behind a slow read.
     * A late book is a late book; a queue of them is an outage.
     *
     * <p>100ms is what a partial-book stream is usually published at -- fast enough that
     * the ladder feels live, slow enough that it is not one frame per event.
     */
    @Scheduled(fixedDelayString = "${exchange.market-ws.depth-interval-ms:100}")
    public void publish() {
        Map<String, List<Subscription>> bySymbol = sockets.watched(DepthChannel.NAME).stream()
                .collect(Collectors.groupingBy(Subscription::symbol));

        // Drops watermarks for markets nobody here is watching any more, so the map cannot
        // grow to hold every symbol this instance has ever served.
        published.keySet().retainAll(bySymbol.keySet());

        bySymbol.forEach(this::publishSymbol);
    }

    private void publishSymbol(String symbol, List<Subscription> watching) {
        // Distinct groupings, insertion-ordered, so two people on the same bucket size are
        // one grouping of the ladder rather than two.
        Set<Long> groupings = watching.stream()
                .map(Subscription::grouping)
                .filter(grouping -> !grouping.isEmpty())
                .map(DepthChannel::parse)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        boolean wantUngrouped = watching.stream()
                .anyMatch(subscription -> subscription.grouping().isEmpty());

        // One read for every view. Fetching the grouped and ungrouped ladders separately
        // would not merely cost twice as much: market-data rewrites this document
        // constantly, so the second fetch can land the other side of a write and the two
        // would then describe different books -- under one watermark, with nothing to say
        // so. Every frame below comes from the same document at the same sequence.
        DepthService.Views views = channel
                .currentViews(groupings, wantUngrouped, symbol)
                .orElse(null);
        if (views == null || views.any() == null || alreadySent(symbol, views.any())) {
            return;
        }
        published.put(symbol, views.any().lastSequence());

        for (Subscription subscription : watching) {
            Depth view = subscription.grouping().isEmpty()
                    ? views.ungrouped()
                    : views.grouped().get(DepthChannel.parse(subscription.grouping()));
            if (view != null) {
                sockets.broadcast(subscription, DepthChannel.frameOf(view));
            }
        }
    }

    private boolean alreadySent(String symbol, Depth book) {
        Long last = published.get(symbol);
        return last != null && last.longValue() == book.lastSequence();
    }
}
