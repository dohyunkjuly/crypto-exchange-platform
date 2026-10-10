package com.exchange.spot.openapi.ws.depth;

import com.exchange.common.money.Money;
import com.exchange.contracts.spot.v1.DepthLevelChanged;
import com.exchange.contracts.spot.v1.Side;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Folds a stream of level changes into one frame per symbol per flush.
 *
 * <p>A level touched twice in a window keeps its latest size -- the quantities are absolute, so
 * the last one is the whole truth. {@code firstSequence} and {@code lastSequence} span every
 * change folded in, which is what a client checks for continuity: the next frame's first must
 * be this frame's last plus one.
 *
 * <p>Pure and single-threaded by contract: the consumer adds on its listener thread and the
 * scheduler drains, so callers synchronise on the instance.
 */
final class DepthUpdateBatcher {

    private static final class Batch {
        long first = Long.MAX_VALUE;
        long last = Long.MIN_VALUE;
        final Map<Long, Long> bids = new LinkedHashMap<>();
        final Map<Long, Long> asks = new LinkedHashMap<>();
    }

    private final Map<String, Batch> bySymbol = new LinkedHashMap<>();

    void add(DepthLevelChanged change) {
        Batch batch = bySymbol.computeIfAbsent(change.getSymbol(), s -> new Batch());
        batch.first = Math.min(batch.first, change.getSequence());
        batch.last = Math.max(batch.last, change.getSequence());
        (change.getSide() == Side.SIDE_BUY ? batch.bids : batch.asks).put(change.getPrice(), change.getQuantity());
    }

    /** Every pending frame, oldest symbol first, and nothing left behind. */
    List<DepthUpdateData> drain() {
        List<DepthUpdateData> frames = new ArrayList<>(bySymbol.size());
        bySymbol.forEach((symbol, batch) -> frames.add(new DepthUpdateData(symbol, batch.first, batch.last,
                levels(batch.bids), levels(batch.asks))));
        bySymbol.clear();
        return frames;
    }

    private static List<List<String>> levels(Map<Long, Long> side) {
        List<List<String>> out = new ArrayList<>(side.size());
        side.forEach((price, quantity) -> out.add(List.of(Money.toDecimal(price), Money.toDecimal(quantity))));
        return out;
    }
}
