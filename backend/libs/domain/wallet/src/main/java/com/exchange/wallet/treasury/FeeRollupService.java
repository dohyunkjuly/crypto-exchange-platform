package com.exchange.wallet.treasury;

import com.exchange.wallet.ledger.LedgerEntryRepository;
import com.exchange.wallet.ledger.LedgerEntryType;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Summarises customer-side fee flow into {@link FeeHourlyTotal}, one closed hour at a time,
 * so a sweep never sums the ledger from the beginning of time (docs/reference/treasury.md).
 *
 * <p><b>An hour is rolled up only once it is safely closed.</b> A transaction that began
 * before the hour ended can commit after it, writing a row with a timestamp inside an hour
 * already summarised, and that row would then be in neither half of the sum. {@link #MARGIN}
 * is how long a settlement transaction is allowed to take; the hour is closed after it.
 *
 * <p><b>Hours are rolled in order and never skipped.</b> The watermark is the newest stored
 * hour, so a gap would be read as "already done" and its rows lost. An hour with no fees in
 * it writes nothing, so the watermark does not advance and that hour is simply re-examined --
 * cheap, since it is empty.
 */
@Service
public class FeeRollupService {

    /** What customers pay and are paid. Never a house entry type. */
    public static final Set<LedgerEntryType> FEE_FLOW =
            Set.of(LedgerEntryType.FEE, LedgerEntryType.REBATE);

    /** Longer than any settlement transaction, so nothing commits into a closed hour. */
    static final Duration MARGIN = Duration.ofMinutes(10);

    private final LedgerEntryRepository ledger;
    private final FeeHourlyTotalRepository totals;

    public FeeRollupService(LedgerEntryRepository ledger, FeeHourlyTotalRepository totals) {
        this.ledger = ledger;
        this.totals = totals;
    }

    /**
     * The instant the rollups cover up to, and where the live half of a sum must start.
     * Nothing has been rolled up yet: the epoch, so the live half is everything.
     */
    public Instant rolledThrough() {
        return totals.newestPeriodStart().map(h -> h.plus(1, ChronoUnit.HOURS)).orElse(Instant.EPOCH);
    }

    /**
     * Rolls up every hour that has closed, oldest first, stopping at the margin.
     *
     * <p>The first ever run starts at the oldest fee row rather than at now, or the history
     * before it would be in neither half of a sum forever after.
     *
     * @return how many hours were summarised
     */
    @Transactional
    public int rollUpClosedHours(Instant now) {
        Instant closedThrough = now.minus(MARGIN).truncatedTo(ChronoUnit.HOURS);
        Optional<Instant> start = totals.newestPeriodStart()
                .map(h -> h.plus(1, ChronoUnit.HOURS))
                .or(() -> ledger.oldestOf(FEE_FLOW).map(o -> o.truncatedTo(ChronoUnit.HOURS)));
        if (start.isEmpty()) {
            return 0;
        }

        Instant hour = start.get();
        int rolled = 0;
        while (hour.isBefore(closedThrough)) {
            rollUp(hour);
            hour = hour.plus(1, ChronoUnit.HOURS);
            rolled++;
        }
        return rolled;
    }

    /**
     * One hour, recomputed from the ledger. Safe to re-run: an hour already stored is left
     * alone rather than added to, so a backfill over a range that overlaps cannot double it.
     */
    @Transactional
    public void rollUp(Instant hour) {
        Instant start = hour.truncatedTo(ChronoUnit.HOURS);
        if (totals.existsByPeriodStart(start)) {
            return;
        }
        Instant end = start.plus(1, ChronoUnit.HOURS);
        List<FeeHourlyTotal> rows = new ArrayList<>();
        for (Object[] row : ledger.sumByAssetAndTypeBetween(FEE_FLOW, start, end)) {
            rows.add(new FeeHourlyTotal(start, (Long) row[0], (LedgerEntryType) row[1],
                    ((Number) row[2]).longValue()));
        }
        totals.saveAll(rows);
    }

    /**
     * What one asset earned over the hours already rolled up, as a positive amount.
     *
     * <p>Stored amounts are signed ledger sums -- a fee debits a trader and is negative, a
     * rebate credits one and is positive -- so earned is their negation, done here rather
     * than in each caller.
     */
    public long earnedBefore(Long assetId, Instant end) {
        return -totals.sumBefore(assetId, end);
    }
}
