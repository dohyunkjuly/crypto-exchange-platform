package com.exchange.spot.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TimeRangeTest {

    private static final Instant NOW = Instant.parse("2026-09-12T00:00:00Z");

    @Test
    void exactlyNinetyDaysIsAllowedAndOneSecondMoreIsNot() {
        new TimeRange(NOW.minus(Duration.ofDays(90)), NOW);
        assertThatThrownBy(() -> new TimeRange(NOW.minus(Duration.ofDays(90)).minusSeconds(1), NOW))
                .isInstanceOf(InvalidTimeRangeException.class);
    }

    @Test
    void aBackwardsOrEmptyRangeIsRejected() {
        assertThatThrownBy(() -> new TimeRange(NOW, NOW.minusSeconds(1)))
                .isInstanceOf(InvalidTimeRangeException.class);
        assertThatThrownBy(() -> new TimeRange(NOW, NOW))
                .isInstanceOf(InvalidTimeRangeException.class);
    }

    @Test
    void missingEndsDefaultToTheLastWeek() {
        TimeRange range = TimeRange.of(null, null, NOW);
        assertThat(range.to()).isEqualTo(NOW);
        assertThat(range.from()).isEqualTo(NOW.minus(Duration.ofDays(7)));
    }
}
