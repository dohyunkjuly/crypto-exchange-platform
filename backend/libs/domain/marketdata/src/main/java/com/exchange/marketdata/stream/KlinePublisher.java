package com.exchange.marketdata.stream;

import com.exchange.marketdata.candle.Candle;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Pushes the bars being drawn to everyone watching them. The {@code DepthPublisher} pattern,
 * with two differences.
 *
 * <p><b>Slower than depth on purpose</b> -- half a second rather than a tenth: a lagging ladder
 * looks broken, while a bar only changes when a trade prints. Not slower than that, because this
 * interval is the dominant term in how far behind a bar runs (everything upstream is tens of
 * milliseconds) and it is what a client sees against the {@code trade} channel, which reaches
 * the socket straight off the listener thread.
 *
 * <p><b>Keyed by subscription, not symbol</b> -- one market is commonly watched at several
 * intervals at once, and each is its own series with its own watermark.
 *
 * <p>An unchanged bar sends nothing: between trades a candle is a fixed document. The check is
 * the bucket's {@code lastSequence}; a new subscriber gets the current bar from
 * {@link KlineChannel#initialFrame} instead of waiting.
 */
@Component
public class KlinePublisher {

    private final KlineChannel channel;
    private final MarketFanout sockets;

    /** {@code channel:symbol:interval:bucket} -&gt; the sequence last broadcast for that
     *  bar. Per bucket rather than per series, so the bar that just closed and the one that
     *  just opened are tracked independently -- which is what lets a closing update still be
     *  sent after the next bucket exists. */
    private final Map<String, Long> published = new ConcurrentHashMap<>();

    public KlinePublisher(KlineChannel channel, MarketFanout sockets) {
        this.channel = channel;
        this.sockets = sockets;
    }

    @Scheduled(fixedDelayString = "${exchange.market-ws.kline-interval-ms:500}")
    public void publish() {
        Set<Subscription> watched = sockets.watched(KlineChannel.NAME);
        // Forget bars nobody is watching any more, so this cannot grow to hold every
        // bucket this instance has ever served.
        published.keySet().removeIf(key -> watched.stream().noneMatch(
                subscription -> key.startsWith(prefix(subscription))));

        for (Subscription subscription : watched) {
            for (Candle candle : channel.recent(subscription)) {
                broadcastIfMoved(subscription, candle);
            }
        }
    }

    private void broadcastIfMoved(Subscription subscription, Candle candle) {
        String key = prefix(subscription) + candle.getOpenTime().toEpochMilli();
        Long last = published.get(key);
        if (last != null && last.longValue() == candle.getLastSequence()) {
            return;
        }
        published.put(key, candle.getLastSequence());
        sockets.broadcast(subscription, channel.frameOf(candle));
    }

    private static String prefix(Subscription subscription) {
        return subscription.symbol() + ":" + subscription.interval() + ":";
    }
}
