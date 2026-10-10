package com.exchange.spot.copy.stats;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.exchange.common.id.Ulid;
import com.exchange.copy.leader.CopyLeader;
import com.exchange.copy.leader.CopyLeaderRepository;
import com.exchange.copy.leader.LeaderStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LeaderStatsJobsTest {

    private final CopyLeaderRepository leaders = mock(CopyLeaderRepository.class);
    private final LeaderStatsService stats = mock(LeaderStatsService.class);
    private final Valuation valuation = mock(Valuation.class);
    private final LeaderStatsJobs jobs = new LeaderStatsJobs(leaders, stats, valuation);

    @Test
    @DisplayName("the night's figures are for the UTC day that has just ended, for active leaders only, and one "
            + "leader failing does not leave the rest a day stale")
    void everyActiveLeaderForTheDayJustEnded() {
        CopyLeader first = new CopyLeader(Ulid.randomUuid(), 1L, "first", null);
        CopyLeader second = new CopyLeader(Ulid.randomUuid(), 2L, "second", null);
        when(leaders.findByStatusOrderByCreatedAtAsc(LeaderStatus.ACTIVE)).thenReturn(List.of(first, second));
        Instant now = Instant.parse("2026-10-03T00:30:00Z");
        LocalDate ended = LocalDate.parse("2026-10-02");
        doThrow(new IllegalStateException("no balance row")).when(stats).refresh(eq(first), any(), any(), any());

        jobs.run(now);

        verify(leaders).findByStatusOrderByCreatedAtAsc(LeaderStatus.ACTIVE);
        verify(stats).refresh(eq(first), eq(ended), eq(now), any());
        verify(stats).refresh(eq(second), eq(ended), eq(now), any());
    }
}
