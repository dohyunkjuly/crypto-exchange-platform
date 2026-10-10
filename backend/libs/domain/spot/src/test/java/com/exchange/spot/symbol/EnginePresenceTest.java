package com.exchange.spot.symbol;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EnginePresenceTest {

    private final AtomicLong clock = new AtomicLong(Duration.ofHours(1).toNanos());
    private final EnginePresence presence = new EnginePresence(clock::get);

    @Test
    @DisplayName("nothing heard yet is paused: a fresh process assumes no engine until one speaks")
    void silent_until_the_first_heartbeat() {
        assertThat(presence.isLive(0)).isFalse();

        presence.heard(0);
        assertThat(presence.isLive(0)).isTrue();
        assertThat(presence.isLive(1)).isFalse();
    }

    @Test
    @DisplayName("live until SILENCE has passed since the last heartbeat, then paused, then live again on the next")
    void goes_quiet_after_the_silence_window() {
        presence.heard(2);
        clock.addAndGet(EnginePresence.SILENCE.toNanos() - 1);
        assertThat(presence.isLive(2)).isTrue();

        clock.addAndGet(1);
        assertThat(presence.isLive(2)).isFalse();

        presence.heard(2);
        assertThat(presence.isLive(2)).isTrue();
    }
}
