package com.exchange.spot.openapi.ws.depth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The client procedure from the design doc, run against frames as this app builds them: seed
 * from a snapshot, drop frames at or before it, apply in order, and on a gap re-seed. Drops a
 * frame on purpose and shows the book recovers. Lives here because the procedure is part of the
 * contract; a client library is what this test would be ported to.
 */
class DepthResyncProcedureTest {

    /** A minimal client book: bids keyed by price, quantity as a string as it arrives. */
    static final class ClientBook {
        final TreeMap<String, String> bids = new TreeMap<>();
        long sequence;
        int resyncs;

        void seed(long snapshotSequence, List<List<String>> snapshotBids) {
            bids.clear();
            snapshotBids.forEach(l -> bids.put(l.get(0), l.get(1)));
            sequence = snapshotSequence;
            resyncs++;
        }

        /** @return false when the frame cannot follow what is held, and a resync is needed */
        boolean apply(DepthUpdateData frame) {
            if (frame.lastSequence() <= sequence) {
                return true; // older than the snapshot: drop
            }
            if (frame.firstSequence() > sequence + 1) {
                return false; // a gap
            }
            for (List<String> level : frame.bids()) {
                if (level.get(1).equals("0")) {
                    bids.remove(level.get(0));
                } else {
                    bids.put(level.get(0), level.get(1));
                }
            }
            sequence = frame.lastSequence();
            return true;
        }
    }

    private static DepthUpdateData frame(long first, long last, List<List<String>> bids) {
        return new DepthUpdateData("BTCUSDT", first, last, bids, List.of());
    }

    @Test
    @DisplayName("a dropped frame is detected as a gap, the client re-seeds, and the book is right again")
    void recoversFromADroppedFrame() {
        ClientBook book = new ClientBook();
        book.seed(10, List.of(List.of("100", "1"), List.of("99", "5")));

        assertThat(book.apply(frame(9, 10, List.of(List.of("100", "9"))))).isTrue(); // stale: dropped
        assertThat(book.bids.get("100")).isEqualTo("1");
        assertThat(book.apply(frame(11, 12, List.of(List.of("100", "2"))))).isTrue();
        assertThat(book.bids.get("100")).isEqualTo("2");

        // Frame 13-14 is lost on the wire. The next one cannot follow.
        assertThat(book.apply(frame(15, 15, List.of(List.of("99", "0"))))).isFalse();
        assertThat(book.bids).containsKey("99"); // nothing applied from the frame that did not fit

        // Re-seed from a fresh snapshot, which already reflects 13-15, and carry on.
        book.seed(15, List.of(List.of("100", "2")));
        assertThat(book.apply(frame(16, 16, List.of(List.of("101", "4"))))).isTrue();
        assertThat(book.bids).containsOnlyKeys("100", "101");
        assertThat(book.resyncs).isEqualTo(2);
    }
}
