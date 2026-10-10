package com.exchange.spot.openapi.ws.depth;

import static org.assertj.core.api.Assertions.assertThat;

import com.exchange.contracts.spot.v1.DepthLevelChanged;
import com.exchange.contracts.spot.v1.Side;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DepthUpdateBatcherTest {

    private static final long ONE = 100_000_000L;

    private static DepthLevelChanged change(String symbol, long seq, Side side, long price, long qty) {
        return DepthLevelChanged.newBuilder().setSymbol(symbol).setSequence(seq).setSide(side).setPrice(price).setQuantity(qty).build();
    }

    @Test
    @DisplayName("a level touched twice keeps its latest size, and the frame spans every sequence folded in")
    void foldsAWindowIntoOneFramePerSymbol() {
        DepthUpdateBatcher batcher = new DepthUpdateBatcher();
        batcher.add(change("BTCUSDT", 10, Side.SIDE_BUY, 100 * ONE, 2 * ONE));
        batcher.add(change("BTCUSDT", 11, Side.SIDE_SELL, 101 * ONE, ONE));
        batcher.add(change("BTCUSDT", 12, Side.SIDE_BUY, 100 * ONE, 0));
        batcher.add(change("ETHUSDT", 5, Side.SIDE_SELL, 10 * ONE, 3 * ONE));

        List<DepthUpdateData> frames = batcher.drain();
        assertThat(frames).hasSize(2);
        DepthUpdateData btc = frames.get(0);
        assertThat(btc.symbol()).isEqualTo("BTCUSDT");
        assertThat(btc.firstSequence()).isEqualTo(10);
        assertThat(btc.lastSequence()).isEqualTo(12);
        assertThat(btc.bids()).containsExactly(List.of("100", "0"));
        assertThat(btc.asks()).containsExactly(List.of("101", "1"));
        assertThat(batcher.drain()).isEmpty();
    }
}
