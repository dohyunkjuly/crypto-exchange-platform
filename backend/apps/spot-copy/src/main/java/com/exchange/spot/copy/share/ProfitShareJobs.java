package com.exchange.spot.copy.share;

import com.exchange.copy.follow.CopyFollow;
import com.exchange.copy.follow.CopyFollowRepository;
import com.exchange.copy.follow.FollowStatus;
import com.exchange.copy.share.CopyProfitShareService;
import com.exchange.copy.share.ShareTrigger;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The weekly profit share (docs/reference/copy-trading.md), for every copy account still
 * copying: closing and withdrawing settle on their own. Each account is its own transaction, so
 * one that cannot pay or cannot be read leaves the rest settled. A run that is late or repeated
 * settles the same week once: the week is the settlement's key.
 */
@Component
class ProfitShareJobs {

    private static final Logger log = LoggerFactory.getLogger(ProfitShareJobs.class);

    private final CopyFollowRepository follows;
    private final CopyProfitShareService shares;

    ProfitShareJobs(CopyFollowRepository follows, CopyProfitShareService shares) {
        this.follows = follows;
        this.shares = shares;
    }

    @Scheduled(cron = "${exchange.copy.share-cron}", zone = "UTC")
    public void run() {
        run(Instant.now());
    }

    /** @param now the week settled is the one ending on the most recent Monday 00:00 UTC, today included */
    void run(Instant now) {
        LocalDate week = now.atZone(ZoneOffset.UTC).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        int settled = 0;
        for (CopyFollow follow : follows.findByStatus(FollowStatus.ACTIVE)) {
            try {
                if (shares.settle(follow.getId(), ShareTrigger.WEEKLY, week).isPresent()) {
                    settled++;
                }
            } catch (RuntimeException e) {
                log.warn("could not settle the profit share of follow {} for the week of {}", follow.getId(), week, e);
            }
        }
        log.info("settled the profit share of {} copy account(s) for the week of {}", settled, week);
    }
}
