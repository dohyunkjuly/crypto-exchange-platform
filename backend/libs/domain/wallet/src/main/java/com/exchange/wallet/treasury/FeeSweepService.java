package com.exchange.wallet.treasury;

import com.exchange.wallet.balance.BalanceRepository;
import com.exchange.wallet.balance.BalanceService;
import com.exchange.wallet.ledger.LedgerEntryRepository;
import com.exchange.wallet.ledger.LedgerEntryType;
import com.exchange.wallet.ledger.ReferenceType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Moves fee revenue into the exchange's own account, off the settlement path
 * (docs/reference/treasury.md).
 *
 * <p><b>It reconciles to a total rather than consuming a window.</b> Each run works out what
 * the account should have been credited -- every FEE a trader paid, less every REBATE one was
 * paid -- and credits the difference against what it has been credited: the sum of its own
 * {@code FEE_SWEEP} entries. There is no watermark and no resume point.
 *
 * <p>That is what makes it safe to crash. A run that died before committing wrote nothing and
 * the next one computes the same difference; a run that died after committing is already
 * counted, so the next one finds nothing to do. Two runs overlapping serialise on the account's
 * own balance row, which is locked before either sum is read.
 *
 * <p>Never the balance: staff move fees on to the treasury, and a sweep that read the balance
 * would sweep the same fees again after every such move.
 */
@Service
public class FeeSweepService {

    private static final Logger log = LoggerFactory.getLogger(FeeSweepService.class);

    private final BalanceService balances;
    private final BalanceRepository balanceRows;
    private final LedgerEntryRepository ledger;
    private final FeeRollupService rollup;

    public FeeSweepService(BalanceService balances, BalanceRepository balanceRows,
                           LedgerEntryRepository ledger, FeeRollupService rollup) {
        this.balances = balances;
        this.balanceRows = balanceRows;
        this.ledger = ledger;
        this.rollup = rollup;
    }

    /** Every asset that has ever carried a fee; an asset with none has nothing to sweep. */
    public List<Long> assetsWithFees() {
        return ledger.assetIdsWithAnyOf(FeeRollupService.FEE_FLOW);
    }

    /**
     * Brings one asset's balance on the fee account up to what has been earned.
     *
     * @return what was credited; zero when the account is already square
     */
    @Transactional
    public long sweep(Long feeAccountId, Long assetId, Instant now) {
        // First, and inside the transaction: two runs must not both read "credited" before
        // either writes. The row may not exist yet, before the first sweep of an asset.
        balanceRows.findForUpdate(feeAccountId, assetId);
        long credited = ledger.sumForAccount(feeAccountId, assetId, LedgerEntryType.FEE_SWEEP);

        long earned = earned(assetId, now);
        long difference = earned - credited;
        if (difference <= 0) {
            // Negative means rebates have outrun fees since the last run. Nothing is paid out
            // of this account, so the next run's larger `earned` absorbs it rather than
            // debiting now and leaving the ledger with a movement no customer row explains.
            return 0;
        }
        String key = "fee-sweep:" + assetId + ":" + earned;
        balances.sweepFees(feeAccountId, assetId, difference, ReferenceType.TRADE, null, key);
        log.info("swept {} of asset {} into the fee account ({} earned, {} swept before)",
                difference, assetId, earned, credited);
        return difference;
    }

    /**
     * What the books say, checked against what they are derived from.
     *
     * <p>Deliberately reads the ledger from the beginning rather than the summaries: a check
     * that trusted the same rollups the sweep trusts could not tell you they were wrong. It is
     * the expensive query in here, which is why it runs nightly and the sweep does not.
     *
     * @param swept what the sweep has credited the fee account, whatever has moved on since
     * @param viaSummaries what {@link #earned} says, summaries and all
     * @param fromLedger what the customer-side rows say on their own
     */
    public record FeeCheck(Long assetId, long swept, long viaSummaries, long fromLedger) {

        /** The summaries agree with the ledger they summarise. A mismatch means a rolled-up
         *  hour is wrong -- repair it and the next sweep corrects itself. */
        public boolean summariesAgree() {
            return viaSummaries == fromLedger;
        }

        /** Nothing was ever credited that customers did not pay. Lagging behind is expected
         *  between sweeps; being ahead is money that no customer row explains. */
        public boolean withinWhatWasEarned() {
            return swept <= fromLedger;
        }

        public boolean ok() {
            return summariesAgree() && withinWhatWasEarned();
        }
    }

    /** One {@link FeeCheck} per asset that has ever carried a fee. */
    public List<FeeCheck> verify(Long feeAccountId, Instant now) {
        List<FeeCheck> checks = new ArrayList<>();
        for (Long assetId : assetsWithFees()) {
            long swept = ledger.sumForAccount(feeAccountId, assetId, LedgerEntryType.FEE_SWEEP);
            checks.add(new FeeCheck(assetId, swept, earned(assetId, now), earnedFromLedger(assetId, now)));
        }
        return checks;
    }

    /** Earned, from the customer-side rows alone -- no summaries, all of history. */
    public long earnedFromLedger(Long assetId, Instant now) {
        return -ledger.sumByAssetBetween(FeeRollupService.FEE_FLOW, assetId, Instant.EPOCH, now);
    }

    /**
     * What customers have paid in fees for this asset, net of rebates, since the beginning.
     *
     * <p>Two halves that must not overlap: the hours already summarised, and the ledger from
     * the end of those hours onwards. The boundary is half-open at both ends -- an hour in
     * both halves would be counted twice, and that is the one error here that repeats rather
     * than corrects itself.
     */
    public long earned(Long assetId, Instant now) {
        Instant rolledThrough = rollup.rolledThrough();
        long summarised = rollup.earnedBefore(assetId, rolledThrough);
        long live = -ledger.sumByAssetBetween(FeeRollupService.FEE_FLOW, assetId, rolledThrough, now);
        return summarised + live;
    }
}
