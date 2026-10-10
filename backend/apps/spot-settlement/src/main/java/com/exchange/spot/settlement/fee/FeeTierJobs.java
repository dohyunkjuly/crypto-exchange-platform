package com.exchange.spot.settlement.fee;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The nightly fee-tier run (docs/reference/fee-tiers.md): recount the last few closed days
 * from {@code spot_trades}, then place every family on the ladder from the trailing thirty.
 *
 * <p>Here rather than in admin-bff for the same reason as {@link
 * com.exchange.spot.settlement.treasury.TreasuryJobs}: it is derived from what settlement
 * wrote, and it is off the settlement path -- no event waits on it. A missed night costs
 * nothing but freshness: the next run recounts the same days and reaches the same answer.
 *
 * <p>Three days, not one, so a trade that settled after midnight still lands in its own day,
 * and two missed nights are caught up without anyone noticing.
 */
@Component
public class FeeTierJobs {

    private static final Logger log = LoggerFactory.getLogger(FeeTierJobs.class);

    static final int WINDOW_DAYS = 30;
    static final int RECOUNT_DAYS = 3;

    private final VolumeRecountService recount;
    private final TierRefreshService refresh;

    public FeeTierJobs(VolumeRecountService recount, TierRefreshService refresh) {
        this.recount = recount;
        this.refresh = refresh;
    }

    @Scheduled(cron = "${exchange.fee.tier-cron}", zone = "UTC")
    public void run() {
        run(Instant.now());
    }

    void run(Instant now) {
        LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
        for (int back = RECOUNT_DAYS; back >= 1; back--) {
            LocalDate day = today.minusDays(back);
            try {
                int accounts = recount.recount(day);
                log.info("recounted {}: {} account(s) traded", day, accounts);
            } catch (RuntimeException e) {
                // Yesterday's tiers are worth more than a clean run: the next night recounts this day again.
                log.warn("fee volume recount failed for {}", day, e);
            }
        }
        try {
            int placed = refresh.refresh(today.minusDays(WINDOW_DAYS), today.minusDays(1), now);
            log.info("placed {} master(s) on the fee ladder", placed);
        } catch (RuntimeException e) {
            log.warn("fee tier refresh failed; the next run recomputes every family", e);
        }
    }
}
