package com.exchange.marketdata.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


import com.exchange.marketdata.depth.DepthGroupings;
import com.exchange.marketdata.candle.Market;
import com.exchange.marketdata.depth.Depth;
import com.exchange.marketdata.depth.DepthLevel;
import com.exchange.marketdata.depth.DepthService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * How many times the publisher reads MongoDB, and what it does with what comes back.
 *
 * <p>A unit test because the property under test is invisible from outside: serving the
 * grouped and ungrouped ladders from two separate reads produces correct-looking frames
 * almost always, and only disagrees when a read lands either side of one of
 * {@code market-data}'s writes. No integration test would catch that reliably -- the only
 * way to assert it is to count the reads.
 */
@ExtendWith(MockitoExtension.class)
class DepthPublisherTest {

    private static final String SYMBOL = "BTCUSDT";
    private static final long ONE = 100_000_000L;

    @Mock
    private DepthService depth;

    @Mock
    private DepthGroupings catalog;

    @Mock
    private MarketFanout sockets;

    private DepthPublisher publisher() {
        return new DepthPublisher(new DepthChannel(depth, catalog), sockets);
    }

    private static Depth view(long bestBid, long sequence) {
        return new Depth(SYMBOL, List.of(new DepthLevel(bestBid, ONE)), List.of(), sequence);
    }

    /**
     * The point. One market watched both raw and grouped is one fetch of the ladder -- the
     * ungrouped view is taken from the same document rather than fetched again, so both
     * frames describe the same book at the same sequence.
     */
    @Test
    void aMixOfGroupedAndUngroupedWatchersCostsOneRead() {
        Subscription raw = Subscription.of(DepthChannel.NAME, SYMBOL);
        Subscription grouped = new Subscription(DepthChannel.NAME, SYMBOL, "", "1");
        when(sockets.watched(DepthChannel.NAME)).thenReturn(Set.of(raw, grouped));
        when(depth.views(eq(Market.SPOT), eq(SYMBOL), any(), eq(true), anyInt()))
                .thenReturn(Optional.of(new DepthService.Views(
                        Map.of(ONE, view(101 * ONE, 5)), view(101 * ONE + 25_000_000L, 5))));

        publisher().publish();

        verify(depth, times(1)).views(any(), anyString(), any(), anyBoolean(), anyInt());
        // The single-read path only means something if the split ones stay unused.
        verify(depth, never()).of(any(), anyString(), anyInt());
        verify(depth, never()).grouped(any(), anyString(), anyLong(), anyInt());
    }

    /** Each subscription gets the view it asked for, not whichever came first. */
    @Test
    void eachSubscriptionReceivesItsOwnView() {
        Subscription raw = Subscription.of(DepthChannel.NAME, SYMBOL);
        Subscription grouped = new Subscription(DepthChannel.NAME, SYMBOL, "", "1");
        when(sockets.watched(DepthChannel.NAME)).thenReturn(Set.of(raw, grouped));
        when(depth.views(eq(Market.SPOT), eq(SYMBOL), any(), eq(true), anyInt()))
                .thenReturn(Optional.of(new DepthService.Views(
                        Map.of(ONE, view(101 * ONE, 5)), view(101 * ONE + 25_000_000L, 5))));

        publisher().publish();

        ArgumentCaptor<Subscription> subscriptions = ArgumentCaptor.captor();
        ArgumentCaptor<WsMessage<?>> frames = ArgumentCaptor.captor();
        verify(sockets, times(2)).broadcast(subscriptions.capture(), frames.capture());

        Map<Subscription, WsMessage<?>> sent = new java.util.HashMap<>();
        for (int i = 0; i < subscriptions.getAllValues().size(); i++) {
            sent.put(subscriptions.getAllValues().get(i), frames.getAllValues().get(i));
        }
        assertThat(bestBidOf(sent.get(raw))).isEqualTo("101.25");   // the raw ladder
        assertThat(bestBidOf(sent.get(grouped))).isEqualTo("101");  // bucketed to 1
    }

    /** Nobody grouping means the cheap precomputed path stays untouched -- a page that
     *  never opens the selector costs what it did before groupings existed. */
    @Test
    void whenNobodyGroupsTheLadderIsNotRead() {
        when(sockets.watched(DepthChannel.NAME))
                .thenReturn(Set.of(Subscription.of(DepthChannel.NAME, SYMBOL)));
        when(depth.views(eq(Market.SPOT), eq(SYMBOL), eq(Set.of()), eq(true), anyInt()))
                .thenReturn(Optional.of(new DepthService.Views(Map.of(), view(101 * ONE, 5))));

        publisher().publish();

        ArgumentCaptor<java.util.Collection<Long>> groupings = ArgumentCaptor.captor();
        verify(depth).views(any(), anyString(), groupings.capture(), eq(true), anyInt());
        assertThat(groupings.getValue()).isEmpty();
    }

    /** An unchanged book sends nothing: the frames are full refreshes, so resending an
     *  identical one is pure waste on a market that is simply quiet. */
    @Test
    void anUnchangedBookIsNotResent() {
        when(sockets.watched(DepthChannel.NAME))
                .thenReturn(Set.of(Subscription.of(DepthChannel.NAME, SYMBOL)));
        when(depth.views(any(), anyString(), any(), anyBoolean(), anyInt()))
                .thenReturn(Optional.of(new DepthService.Views(Map.of(), view(101 * ONE, 5))));

        DepthPublisher publisher = publisher();
        publisher.publish();
        publisher.publish();

        verify(sockets, times(1)).broadcast(any(), any());
    }

    private static String bestBidOf(WsMessage<?> frame) {
        return ((DepthData) frame.data()).bids().get(0).price();
    }
}
