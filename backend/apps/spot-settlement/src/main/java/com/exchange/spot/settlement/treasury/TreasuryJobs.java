package com.exchange.spot.settlement.treasury;

import com.exchange.user.identity.User;
import com.exchange.user.identity.UserRepository;
import com.exchange.wallet.treasury.FeeRollupService;
import com.exchange.wallet.treasury.FeeSweepService;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fee revenue reaching {@code fees@exchange}, and the hourly summaries that keep it cheap
 * (docs/reference/treasury.md).
 *
 * <p><b>Here rather than in admin-bff</b> because this writes balances, and settlement is the
 * only thing that does ([scalability.md]). It is off the settlement path all the same: it
 * consumes no events and holds no locks a fill waits on -- only the fee account's own row.
 *
 * <p>Neither job needs to have run for trading to be correct. The ledger is already complete;
 * these two only move the derived total along, which is why a missed run costs nothing but
 * freshness.
 */
@Component
public class TreasuryJobs {

    private static final Logger log = LoggerFactory.getLogger(TreasuryJobs.class);

    private final FeeSweepService sweeps;
    private final FeeRollupService rollup;
    private final UserRepository users;
    private final String feeAccountEmail;
    private volatile Long feeAccountId;

    public TreasuryJobs(FeeSweepService sweeps, FeeRollupService rollup, UserRepository users,
                        @Value("${exchange.treasury.fee-account-email}") String feeAccountEmail) {
        this.sweeps = sweeps;
        this.rollup = rollup;
        this.users = users;
        this.feeAccountEmail = feeAccountEmail;
    }

    /** Often enough that the admin page is never far behind; the work is one row per asset. */
    @Scheduled(fixedDelayString = "${exchange.treasury.sweep-interval-ms}")
    public void sweep() {
        Instant now = Instant.now();
        for (Long assetId : sweeps.assetsWithFees()) {
            try {
                sweeps.sweep(feeAccountId(), assetId, now);
            } catch (RuntimeException e) {
                // One asset failing must not stop the others, and nothing is lost: the next
                // run recomputes the same difference.
                log.warn("fee sweep failed for asset {}", assetId, e);
            }
        }
    }

    /** Summarising a closed hour is what stops a sweep summing the ledger from the start. */
    @Scheduled(fixedDelayString = "${exchange.treasury.rollup-interval-ms}")
    public void rollUp() {
        try {
            int hours = rollup.rollUpClosedHours(Instant.now());
            if (hours > 0) {
                log.info("summarised {} closed hour(s) of fee flow", hours);
            }
        } catch (RuntimeException e) {
            log.warn("fee rollup failed; the next run resumes from the same hour", e);
        }
    }

    /**
     * Reconciliation: what the fee account holds against what customers actually paid, read
     * from the ledger rather than from the summaries the sweep uses (docs/reference/treasury.md).
     *
     * <p>This is the check that catches everything the design cannot rule out -- a wrong
     * rolled-up hour, a row that landed in an hour already summarised, a filter that counted
     * the wrong entry type, anything else writing to the account. It sums all of history, so
     * it runs nightly rather than every minute.
     */
    @Scheduled(cron = "${exchange.treasury.verify-cron}", zone = "UTC")
    public void verify() {
        for (FeeSweepService.FeeCheck check : sweeps.verify(feeAccountId(), Instant.now())) {
            if (!check.summariesAgree()) {
                log.error("fee summaries disagree with the ledger for asset {}: {} summarised, "
                        + "{} in the ledger. Re-roll the affected hours; the next sweep corrects itself.",
                        check.assetId(), check.viaSummaries(), check.fromLedger());
            }
            if (!check.withinWhatWasEarned()) {
                log.error("the sweep has credited more of asset {} than customers paid: {} swept, "
                        + "{} earned.",
                        check.assetId(), check.swept(), check.fromLedger());
            }
        }
    }

    /** Resolved once: the row is created by a migration and never changes. */
    private Long feeAccountId() {
        Long id = feeAccountId;
        if (id == null) {
            id = users.findByEmail(feeAccountEmail).map(User::getId).orElseThrow(
                    () -> new IllegalStateException(feeAccountEmail + " is missing; see V8__system_accounts.sql"));
            feeAccountId = id;
        }
        return id;
    }
}
