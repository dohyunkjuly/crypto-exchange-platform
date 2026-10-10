package com.exchange.spot.copy.stats;

import com.exchange.copy.leader.CopyLeader;
import com.exchange.copy.leader.CopyLeaderRepository;
import com.exchange.copy.leader.LeaderStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The nightly run of every active leader's figures (docs/reference/copy-trading.md).
 *
 * <p>Here because the copier is the app that already reads every table it needs and has no
 * request waiting on it. A missed night costs a day of freshness and nothing else: the next
 * snapshot is measured against the last one there is, however old.
 */
@Component
class LeaderStatsJobs {

    private static final Logger log = LoggerFactory.getLogger(LeaderStatsJobs.class);

    private final CopyLeaderRepository leaders;
    private final LeaderStatsService stats;
    private final Valuation valuation;

    LeaderStatsJobs(CopyLeaderRepository leaders, LeaderStatsService stats, Valuation valuation) {
        this.leaders = leaders;
        this.stats = stats;
        this.valuation = valuation;
    }

    @Scheduled(cron = "${exchange.copy.stats-cron}", zone = "UTC")
    public void run() {
        run(Instant.now());
    }

    void run(Instant now) {
        LocalDate day = now.atZone(ZoneOffset.UTC).toLocalDate().minusDays(1);
        Valuation.Prices prices = valuation.at(now);
        int done = 0;
        for (CopyLeader leader : leaders.findByStatusOrderByCreatedAtAsc(LeaderStatus.ACTIVE)) {
            try {
                stats.refresh(leader, day, now, prices);
                done++;
            } catch (RuntimeException e) {
                // One leader failing must not leave the rest a day stale.
                log.warn("could not refresh the figures of leader {}", leader.getId(), e);
            }
        }
        log.info("refreshed the figures of {} leader(s) for {}", done, day);
    }
}
