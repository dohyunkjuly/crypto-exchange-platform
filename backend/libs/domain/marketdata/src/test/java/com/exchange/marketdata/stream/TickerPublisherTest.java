package com.exchange.marketdata.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.exchange.marketdata.ticker.Ticker;
import com.exchange.marketdata.ticker.TickerSnapshot;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * What the ticker publisher pushes, and what it declines to.
 *
 * <p>The cache is mocked, which is the whole point of the design under test: this publisher
 * must never reach past it to {@code TickerService}, because the REST side already pays for
 * the computation and doing it per watched symbol per tick would undo that.
 */
@ExtendWith(MockitoExtension.class)
class TickerPublisherTest {

    private static final String SYMBOL = "BTCUSDT";

    @Mock
    private TickerSnapshot cache;

    @Mock
    private MarketFanout sockets;

    private TickerPublisher publisher() {
        return new TickerPublisher(new TickerChannel(cache), sockets);
    }

    private static Ticker ticker(long lastPrice, long volume) {
        return new Ticker(SYMBOL, 100, 110, 90, lastPrice, 5, 500, volume, volume, 7,
                Instant.EPOCH, Instant.EPOCH.plusSeconds(86_400));
    }

    @Test
    void pushesTheSummaryToEverySubscriberWhenItMoves() {
        Subscription watching = Subscription.of(TickerChannel.NAME, SYMBOL);
        when(sockets.watched(TickerChannel.NAME)).thenReturn(Set.of(watching));
        when(cache.of(SYMBOL)).thenReturn(Optional.of(ticker(105, 10)),
                Optional.of(ticker(107, 12)));

        TickerPublisher publisher = publisher();
        publisher.publish();
        publisher.publish();

        verify(sockets, times(2)).broadcast(any(), any());
    }

    /** A market with no trades in a refresh produces an identical view, and resending it is
     *  bytes for no information. */
    @Test
    void anUnchangedSummaryIsNotResent() {
        when(sockets.watched(TickerChannel.NAME))
                .thenReturn(Set.of(Subscription.of(TickerChannel.NAME, SYMBOL)));
        when(cache.of(SYMBOL)).thenReturn(Optional.of(ticker(105, 10)));

        TickerPublisher publisher = publisher();
        publisher.publish();
        publisher.publish();
        publisher.publish();

        verify(sockets, times(1)).broadcast(any(), any());
    }

    /**
     * The comparison must cover the whole view, not just the price. A trade at an unchanged
     * price still moves volume -- common on a thin market -- and comparing only {@code
     * lastPrice} would silently stop updating the header for exactly those markets.
     */
    @Test
    void volumeMovingAtAnUnchangedPriceStillPublishes() {
        when(sockets.watched(TickerChannel.NAME))
                .thenReturn(Set.of(Subscription.of(TickerChannel.NAME, SYMBOL)));
        when(cache.of(SYMBOL)).thenReturn(Optional.of(ticker(105, 10)),
                Optional.of(ticker(105, 13)));

        TickerPublisher publisher = publisher();
        publisher.publish();
        publisher.publish();

        verify(sockets, times(2)).broadcast(any(), any());
    }

    /** Nobody watching means no work: not a computation whose result is thrown away. */
    @Test
    void nothingWatchedIsNoWork() {
        when(sockets.watched(TickerChannel.NAME)).thenReturn(Set.of());

        publisher().publish();

        verify(sockets, never()).broadcast(any(), any());
        verify(cache, never()).of(any());
    }

    /** A subscriber gets the summary at subscribe time rather than waiting for the next
     *  trade -- on a quiet market that is minutes of an empty header. */
    @Test
    void subscribingYieldsTheCurrentSummaryImmediately() {
        when(cache.of(SYMBOL)).thenReturn(Optional.of(ticker(105, 10)));

        assertThat(new TickerChannel(cache)
                .initialFrame(Subscription.of(TickerChannel.NAME, SYMBOL)))
                .isPresent();
    }
}
