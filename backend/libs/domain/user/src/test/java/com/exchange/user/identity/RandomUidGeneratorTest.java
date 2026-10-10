package com.exchange.user.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RandomUidGeneratorTest {

    @Test
    @DisplayName("every id is ten digits, and they do not count up")
    void tenDigitsNotACounter() {
        SplittableRandom random = new SplittableRandom(7);
        long previous = 0;
        boolean wentDown = false;
        for (int i = 0; i < 10_000; i++) {
            long id = RandomUidGenerator.pick(x -> false, random);
            assertThat(String.valueOf(id)).hasSize(10);
            wentDown |= id < previous;
            previous = id;
        }
        assertThat(wentDown).isTrue();
    }

    @Test
    @DisplayName("a taken id is drawn again, and the one returned is free")
    void takenIsDrawnAgain() {
        List<Long> drawn = new ArrayList<>();
        // The first draw is "taken": the second one is what comes back.
        long id = RandomUidGenerator.pick(x -> drawn.add(x) && drawn.size() == 1, new SplittableRandom(1));
        assertThat(drawn).hasSize(2);
        assertThat(id).isEqualTo(drawn.get(1));
    }

    @Test
    @DisplayName("gives up after a few draws rather than looping forever")
    void givesUp() {
        assertThatThrownBy(() -> RandomUidGenerator.pick(x -> true, new SplittableRandom(1)))
                .isInstanceOf(IllegalStateException.class);
    }
}
