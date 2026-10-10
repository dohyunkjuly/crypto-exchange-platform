package com.exchange.perf.engine;

import static com.exchange.perf.engine.BenchmarkSupport.*;

import com.exchange.contracts.spot.v1.*;
import com.exchange.perf.report.Report;
import com.exchange.perf.report.Sections;
import com.exchange.spot.matching.engine.MatchingEngine;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * What a resting order costs in heap, and what a snapshot of that book costs the matching
 * thread, at four depths from 5,000 to 2,000,000 orders across 100 markets. In-memory only, on
 * purpose: this is the one question Kafka has nothing to do with. {@code System.gc()} is
 * advisory, so the heap figure is real but approximate.
 */
@Tag("performance")
class MemoryFootprintBenchmark {

    @Test
    void heap_and_snapshot_cost_by_depth() {
        for (int perSymbol : new int[] {50, 500, 2_000, 20_000}) {
            int symbols = 100;
            settle();
            long before = used();
            MatchingEngine engine = fill(symbols, perSymbol);
            settle();
            double bytesPerOrder = (used() - before) / (double) (symbols * perSymbol);

            long t0 = System.nanoTime();
            BookSnapshot snapshot = engine.snapshot(5, 0, 0);
            double buildMs = (System.nanoTime() - t0) / 1_000_000.0;
            if (snapshot.getSerializedSize() < 0) throw new IllegalStateException("unreachable");

            String depth = String.format("%,d orders", symbols * perSymbol);
            Report.value(Sections.ENGINE, Sections.MEMORY_AT + depth, bytesPerOrder, "bytes", depth + " across 100 markets", null);
            Report.value(Sections.ENGINE, Sections.SNAPSHOT_BUILD + depth, buildMs, "ms", "building the protobuf snapshot on the matching thread", null);
            if (perSymbol == 20_000) {
                Report.value(Sections.HEADLINE, Sections.MEMORY_PER_ORDER, bytesPerOrder, "bytes",
                        "2,000,000 resting orders across 100 markets",
                        "Falls as fixed costs spread out, from about 680 bytes at 5,000 orders: ten million resting orders is about 5.3 GB of book.");
            }
        }
    }

    private static MatchingEngine fill(int symbols, int perSymbol) {
        MatchingEngine engine = new MatchingEngine();
        for (int s = 0; s < symbols; s++) {
            for (int i = 0; i < perSymbol; i++) {
                engine.handle(Command.newBuilder()
                        .setCommandId(MatchingEngine.toBytes(UUID.randomUUID()))
                        .setSymbol("MEM" + s)
                        .setTimestampUs(1_000_000L)
                        .setPlaceOrder(PlaceOrder.newBuilder()
                                .setOrderId(MatchingEngine.toBytes(UUID.randomUUID()))
                                .setUserId(1).setSide(Side.SIDE_BUY)
                                .setOrderType(OrderType.ORDER_TYPE_LIMIT)
                                .setTimeInForce(TimeInForce.TIME_IN_FORCE_GTC)
                                .setPrice(PRICE0 + i * 100L).setQuantity(QTY)
                                .setMakerFeeBps(-2).setTakerFeeBps(7))
                        .build(), 5);
            }
        }
        return engine;
    }

    private static void settle() {
        for (int i = 0; i < 3; i++) {
            System.gc();
            try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    private static long used() {
        Runtime r = Runtime.getRuntime();
        return r.totalMemory() - r.freeMemory();
    }
}
