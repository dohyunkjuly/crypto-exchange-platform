package com.exchange.wallet.treasury;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;

import com.exchange.wallet.balance.Balance;
import com.exchange.wallet.balance.BalanceRepository;
import com.exchange.wallet.balance.BalanceService;
import com.exchange.wallet.ledger.LedgerEntryRepository;
import com.exchange.wallet.ledger.LedgerEntryType;
import com.exchange.wallet.ledger.ReferenceType;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * The sweep reconciles a total; what it must never do is count an hour twice or move money
 * that the trader-side rows do not explain.
 */
class FeeSweepServiceTest {

    private static final Long FEES = 398L;
    private static final Long USDT = 1L;
    private static final Instant NOW = Instant.parse("2026-09-23T14:31:00Z");
    private static final Instant HOUR_14 = Instant.parse("2026-09-23T14:00:00Z");

    private final BalanceService balances = Mockito.mock(BalanceService.class);
    private final BalanceRepository balanceRows = Mockito.mock(BalanceRepository.class);
    private final LedgerEntryRepository ledger = Mockito.mock(LedgerEntryRepository.class);
    private final FeeHourlyTotalRepository totals = Mockito.mock(FeeHourlyTotalRepository.class);
    private final FeeRollupService rollup = new FeeRollupService(ledger, totals);
    private final FeeSweepService sweeps = new FeeSweepService(balances, balanceRows, ledger, rollup);

    @Test
    @DisplayName("credits the difference between what was earned and what was swept before")
    void creditsTheDifference() {
        earned(1_000L, 400L);          // 1000 summarised, 400 live
        holds(900L);

        assertThat(sweeps.sweep(FEES, USDT, NOW)).isEqualTo(500L);
        Mockito.verify(balances).sweepFees(eq(FEES), eq(USDT), eq(500L), eq(ReferenceType.TRADE),
                any(), any());
    }

    @Test
    @DisplayName("an account already square is left alone: a second run moves nothing")
    void squareAccountIsLeftAlone() {
        earned(1_000L, 400L);
        holds(1_400L);

        assertThat(sweeps.sweep(FEES, USDT, NOW)).isZero();
        Mockito.verifyNoInteractions(balances);
    }

    @Test
    @DisplayName("a run that died before committing is repeated, not doubled")
    void aDeadRunIsSimplyRepeated() {
        earned(1_000L, 0L);
        holds(0L);                      // the credit never committed
        assertThat(sweeps.sweep(FEES, USDT, NOW)).isEqualTo(1_000L);

        holds(1_000L);                  // this time it did
        assertThat(sweeps.sweep(FEES, USDT, NOW)).isZero();
    }

    @Test
    @DisplayName("fees moved on to the treasury are not swept again: what was swept counts, not what is held")
    void movedOnIsNotSweptAgain() {
        earned(1_000L, 0L);
        holds(1_000L);
        Balance emptied = Mockito.mock(Balance.class);
        Mockito.when(emptied.getTotal()).thenReturn(0L);   // staff moved all of it to the treasury
        Mockito.when(balanceRows.findForUpdate(FEES, USDT)).thenReturn(Optional.of(emptied));

        assertThat(sweeps.sweep(FEES, USDT, NOW)).isZero();
        Mockito.verifyNoInteractions(balances);
    }

    @Test
    @DisplayName("rebates outrunning fees wait for the next run rather than debiting the account")
    void negativeDifferenceWaits() {
        earned(1_000L, 0L);
        holds(1_200L);

        assertThat(sweeps.sweep(FEES, USDT, NOW)).isZero();
        Mockito.verifyNoInteractions(balances);
    }

    @Test
    @DisplayName("the live half starts where the summarised hours end, so no hour is counted twice")
    void theBoundaryIsHalfOpen() {
        Mockito.when(totals.newestPeriodStart()).thenReturn(Optional.of(HOUR_14));
        Mockito.when(totals.sumBefore(eq(USDT), any())).thenReturn(-1_000L);
        Mockito.when(ledger.sumByAssetBetween(any(), eq(USDT), any(), any())).thenReturn(-400L);

        assertThat(sweeps.earned(USDT, NOW)).isEqualTo(1_400L);

        // Summaries are read up to the end of the newest hour, and the ledger from exactly
        // there -- the same instant, once each.
        Instant boundary = HOUR_14.plus(1, ChronoUnit.HOURS);
        Mockito.verify(totals).sumBefore(USDT, boundary);
        Mockito.verify(ledger).sumByAssetBetween(any(), eq(USDT), eq(boundary), eq(NOW));
    }

    @Test
    @DisplayName("with nothing summarised yet, the live half is all of history")
    void noSummariesMeansEverythingIsLive() {
        Mockito.when(totals.newestPeriodStart()).thenReturn(Optional.empty());
        Mockito.when(totals.sumBefore(eq(USDT), any())).thenReturn(0L);
        Mockito.when(ledger.sumByAssetBetween(any(), eq(USDT), any(), any())).thenReturn(-750L);

        assertThat(sweeps.earned(USDT, NOW)).isEqualTo(750L);
        Mockito.verify(ledger).sumByAssetBetween(any(), eq(USDT), eq(Instant.EPOCH), eq(NOW));
    }

    @Test
    @DisplayName("a summary that disagrees with the ledger it summarises is reported")
    void aWrongSummaryIsCaught() {
        // The summaries say 1,400 earned; the ledger itself says 1,300.
        earned(1_000L, 400L);
        Mockito.when(ledger.assetIdsWithAnyOf(any())).thenReturn(List.of(USDT));
        Mockito.when(ledger.sumByAssetBetween(any(), eq(USDT), eq(Instant.EPOCH), eq(NOW)))
                .thenReturn(-1_300L);

        FeeSweepService.FeeCheck check = sweeps.verify(FEES, NOW).get(0);
        assertThat(check.summariesAgree()).isFalse();
        assertThat(check.ok()).isFalse();
    }

    @Test
    @DisplayName("an account holding more than customers paid is reported; holding less is not")
    void onlyBeingAheadIsAFault() {
        earned(1_000L, 0L);
        Mockito.when(ledger.assetIdsWithAnyOf(any())).thenReturn(List.of(USDT));
        Mockito.when(ledger.sumByAssetBetween(any(), eq(USDT), eq(Instant.EPOCH), eq(NOW)))
                .thenReturn(-1_000L);

        // Behind: the ordinary state between sweeps.
        holdsFor(600L);
        assertThat(sweeps.verify(FEES, NOW).get(0).ok()).isTrue();

        // Ahead: money in the account that no customer row explains.
        holdsFor(1_200L);
        FeeSweepService.FeeCheck ahead = sweeps.verify(FEES, NOW).get(0);
        assertThat(ahead.withinWhatWasEarned()).isFalse();
        assertThat(ahead.ok()).isFalse();
    }

    private void holdsFor(long amount) {
        holds(amount);
    }

    /** Ledger sums are signed: a fee debits the trader, so earning shows as a negative sum. */
    private void earned(long summarised, long live) {
        Mockito.when(totals.newestPeriodStart()).thenReturn(Optional.of(HOUR_14));
        Mockito.when(totals.sumBefore(eq(USDT), any())).thenReturn(-summarised);
        Mockito.when(ledger.sumByAssetBetween(any(), eq(USDT), any(), any())).thenReturn(-live);
    }

    /** What the sweep has credited so far: the fee account's own FEE_SWEEP entries. */
    private void holds(long amount) {
        Mockito.when(ledger.sumForAccount(FEES, USDT, LedgerEntryType.FEE_SWEEP)).thenReturn(amount);
    }
}
