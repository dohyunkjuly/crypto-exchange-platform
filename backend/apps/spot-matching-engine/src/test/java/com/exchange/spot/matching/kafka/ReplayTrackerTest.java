package com.exchange.spot.matching.kafka;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReplayTrackerTest {

    private final ReplayTracker tracker = new ReplayTracker();
    private final TopicPartition p0 = new TopicPartition("exchange.spot.commands.v1", 0);
    private final TopicPartition p1 = new TopicPartition("exchange.spot.commands.v1", 1);

    @Test
    @DisplayName("everything before the committed offset was already published")
    void offsets_below_the_boundary_replay_silently() {
        tracker.beginReplay(p0, 100);

        assertTrue(tracker.isReplaying(p0, 0));
        assertTrue(tracker.isReplaying(p0, 99));
    }

    @Test
    @DisplayName("the committed offset itself has NOT been published — it publishes")
    void the_boundary_offset_is_live() {
        tracker.beginReplay(p0, 100);

        // This is the crash case: matched, but the events never landed. Publishing it
        // again is correct; skipping it would lose a fill forever.
        assertFalse(tracker.isReplaying(p0, 100));
        assertFalse(tracker.isReplaying(p0, 101));
    }

    @Test
    void a_fresh_group_starts_live_with_nothing_to_replay() {
        assertFalse(tracker.beginReplay(p0, 0), "offset 0 means no history to rebuild");
        assertFalse(tracker.isReplaying(p0, 0));
    }

    @Test
    void partitions_track_their_own_boundaries() {
        tracker.beginReplay(p0, 100);
        tracker.beginReplay(p1, 5);

        assertTrue(tracker.isReplaying(p0, 50));
        assertFalse(tracker.isReplaying(p1, 50));
    }

    @Test
    @DisplayName("a revoked partition stops being replayed, so a reassignment starts clean")
    void revoking_forgets_the_boundary() {
        tracker.beginReplay(p0, 100);
        tracker.forget(p0);

        assertFalse(tracker.isReplaying(p0, 50));
    }

    @Test
    void an_unknown_partition_is_never_treated_as_replaying() {
        assertFalse(tracker.isReplaying(p1, 0));
    }

    @Test
    @DisplayName("a partition is live once replay has reached the boundary, and not before")
    void live_once_the_last_replayed_record_is_seen() {
        tracker.beginReplay(p0, 100);
        tracker.resumedAt(p0, 40);
        assertFalse(tracker.isLive(p0));

        tracker.seen(p0, 98);
        assertFalse(tracker.isLive(p0), "one more to replay");
        tracker.seen(p0, 99);
        assertTrue(tracker.isLive(p0), "the record before the boundary was the last replayed one");
    }

    @Test
    @DisplayName("resuming at or past the boundary has nothing to replay: live at once, with no record seen")
    void live_at_once_when_the_snapshot_reaches_the_boundary() {
        tracker.beginReplay(p0, 100);
        tracker.resumedAt(p0, 100);
        assertTrue(tracker.isLive(p0));

        assertFalse(tracker.beginReplay(p1, 0));
        tracker.resumedAt(p1, 0);
        assertTrue(tracker.isLive(p1), "a fresh group with nothing committed");
    }

    @Test
    void a_new_replay_or_a_revoke_takes_the_partition_off_the_air() {
        tracker.beginReplay(p0, 0);
        tracker.resumedAt(p0, 0);
        assertTrue(tracker.isLive(p0));

        tracker.beginReplay(p0, 500);
        assertFalse(tracker.isLive(p0));
        tracker.seen(p0, 499);
        assertTrue(tracker.isLive(p0));

        tracker.forget(p0);
        assertFalse(tracker.isLive(p0));
    }
}
