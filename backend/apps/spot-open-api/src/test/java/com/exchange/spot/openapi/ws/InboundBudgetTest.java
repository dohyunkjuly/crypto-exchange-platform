package com.exchange.spot.openapi.ws;

import static org.assertj.core.api.Assertions.assertThat;

import com.exchange.spot.openapi.ws.InboundBudget.Verdict;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InboundBudgetTest {

    private static final Instant T0 = Instant.parse("2026-09-15T00:00:00Z");

    @Test
    @DisplayName("ten a second pass, the eleventh is refused, and the next second starts clean")
    void windowOfOneSecond() {
        InboundBudget budget = new InboundBudget(10, 10);
        for (int i = 0; i < 10; i++) {
            assertThat(budget.onMessage(T0)).isEqualTo(Verdict.ALLOW);
        }
        assertThat(budget.onMessage(T0.plusMillis(500))).isEqualTo(Verdict.REFUSE);
        assertThat(budget.onMessage(T0.plusSeconds(1))).isEqualTo(Verdict.ALLOW);
    }

    @Test
    @DisplayName("staying over the limit for the grace period disconnects; dropping under it resets the clock")
    void persistentAbuseDisconnects() {
        InboundBudget budget = new InboundBudget(1, 3);
        Instant t = T0;
        for (int s = 0; s < 3; s++) {
            budget.onMessage(t.plusSeconds(s));
            assertThat(budget.onMessage(t.plusSeconds(s))).isEqualTo(Verdict.REFUSE);
        }
        budget.onMessage(t.plusSeconds(3));
        assertThat(budget.onMessage(t.plusSeconds(3))).isEqualTo(Verdict.DISCONNECT);

        InboundBudget calm = new InboundBudget(1, 3);
        calm.onMessage(T0); calm.onMessage(T0);                 // over in second 0
        assertThat(calm.onMessage(T0.plusSeconds(1))).isEqualTo(Verdict.ALLOW); // under in second 1 resets
        calm.onMessage(T0.plusSeconds(5)); 
        assertThat(calm.onMessage(T0.plusSeconds(5))).isEqualTo(Verdict.REFUSE);
    }
}
