package com.exchange.wallet.balance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.exchange.wallet.balance.exception.InsufficientBalanceException;
import com.exchange.wallet.ledger.LedgerEntry;
import com.exchange.wallet.ledger.LedgerEntryRepository;
import com.exchange.wallet.ledger.LedgerEntryType;
import com.exchange.wallet.ledger.ReferenceType;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link BalanceRepository} and {@link LedgerEntryRepository} are mocked, not backed by
 * a real database -- {@code findForUpdate} returning the same mutable {@link Balance}
 * instance across stubbed calls stands in for "the row survives between reads within
 * this test," and {@code save} echoing its argument stands in for a JPA flush. What is
 * under test is {@link BalanceService}'s own orchestration: which field of {@code
 * Balance} each mutator touches, the sign and {@code balanceAfter} it ledgers, and the
 * idempotency short-circuit -- not JPA itself.
 */
@ExtendWith(MockitoExtension.class)
class BalanceServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long ASSET_ID = 2L;

    @Mock
    private BalanceRepository balances;

    @Mock
    private LedgerEntryRepository ledgerEntries;

    @Mock
    private BalanceChangePublisher changes;

    private BalanceService balanceService;

    @BeforeEach
    void setUp() {
        balanceService = new BalanceService(balances, ledgerEntries, changes);
        // lenient: not every test exercises both the single-movement and the batch
        // path, and each uses a different idempotency check.
        lenient().when(balances.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(ledgerEntries.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
    }

    @Test
    void lockMovesAvailableToLockedAndLedgersTheDeltaToAvailable() {
        Balance seed = new Balance(USER_ID, ASSET_ID);
        seed.credit(1_000L);
        when(balances.findForUpdate(USER_ID, ASSET_ID)).thenReturn(Optional.of(seed));

        Balance result = balanceService.lock(USER_ID, ASSET_ID, 400L,
                ReferenceType.ORDER, "order-1", "lock:order-1");

        assertThat(result.getAvailable()).isEqualTo(600L);
        assertThat(result.getLocked()).isEqualTo(400L);

        LedgerEntry entry = captureSavedEntry();
        assertThat(entry.getEntryType()).isEqualTo(LedgerEntryType.ORDER_LOCK);
        assertThat(entry.getAmount()).isEqualTo(-400L);
        assertThat(entry.getBalanceAfter()).isEqualTo(600L); // available after, not total
        assertThat(entry.getReferenceType()).isEqualTo(ReferenceType.ORDER);
        assertThat(entry.getReferenceId()).isEqualTo("order-1");
        assertThat(entry.getIdempotencyKey()).isEqualTo("lock:order-1");
    }

    @Test
    void unlockReturnsToTheStateBeforeTheMatchingLock() {
        Balance seed = new Balance(USER_ID, ASSET_ID);
        seed.credit(1_000L);
        when(balances.findForUpdate(USER_ID, ASSET_ID)).thenReturn(Optional.of(seed));

        balanceService.lock(USER_ID, ASSET_ID, 400L, ReferenceType.ORDER, "order-1", "lock:order-1");
        Balance result = balanceService.unlock(USER_ID, ASSET_ID, 400L,
                ReferenceType.ORDER, "order-1", "unlock:order-1");

        assertThat(result.getAvailable()).isEqualTo(1_000L);
        assertThat(result.getLocked()).isEqualTo(0L);
    }

    @Test
    void settleLockedLedgersAgainstLockedNotAvailable() {
        Balance seed = new Balance(USER_ID, ASSET_ID);
        seed.credit(1_000L);
        seed.lock(400L);
        when(balances.findForUpdate(USER_ID, ASSET_ID)).thenReturn(Optional.of(seed));

        Balance result = balanceService.settleLocked(USER_ID, ASSET_ID, 400L,
                ReferenceType.TRADE, "trade-1", "settle:trade-1");

        assertThat(result.getAvailable()).isEqualTo(600L); // untouched by settleLocked
        assertThat(result.getLocked()).isEqualTo(0L);

        LedgerEntry entry = captureSavedEntry();
        assertThat(entry.getEntryType()).isEqualTo(LedgerEntryType.TRADE_DEBIT);
        assertThat(entry.getAmount()).isEqualTo(-400L);
        assertThat(entry.getBalanceAfter()).isEqualTo(0L); // locked after, not available
    }

    @Test
    void creditOnAFirstEverTouchOfAnAssetCreatesTheRowInsteadOfFailing() {
        when(balances.findForUpdate(USER_ID, ASSET_ID)).thenReturn(Optional.empty());

        Balance result = balanceService.credit(USER_ID, ASSET_ID, 500L,
                ReferenceType.DEPOSIT, "deposit-1", "credit:deposit-1");

        assertThat(result.getAvailable()).isEqualTo(500L);
        LedgerEntry entry = captureSavedEntry();
        assertThat(entry.getEntryType()).isEqualTo(LedgerEntryType.TRADE_CREDIT);
        assertThat(entry.getAmount()).isEqualTo(500L);
        assertThat(entry.getBalanceAfter()).isEqualTo(500L);
    }

    @Test
    void rebateCreditsAvailableButLedgersAsRebateNotTradeCredit() {
        when(balances.findForUpdate(USER_ID, ASSET_ID)).thenReturn(Optional.empty());

        Balance result = balanceService.rebate(USER_ID, ASSET_ID, 5L,
                ReferenceType.TRADE, "trade-1", "rebate:trade-1:maker");

        assertThat(result.getAvailable()).isEqualTo(5L);
        LedgerEntry entry = captureSavedEntry();
        assertThat(entry.getEntryType()).isEqualTo(LedgerEntryType.REBATE);
        assertThat(entry.getAmount()).isEqualTo(5L);
        assertThat(entry.getBalanceAfter()).isEqualTo(5L);
    }

    @Test
    void lockBeyondAvailableThrowsAndWritesNoLedgerEntry() {
        Balance seed = new Balance(USER_ID, ASSET_ID);
        seed.credit(100L);
        when(balances.findForUpdate(USER_ID, ASSET_ID)).thenReturn(Optional.of(seed));

        assertThatThrownBy(() -> balanceService.lock(USER_ID, ASSET_ID, 200L,
                ReferenceType.ORDER, "order-1", "lock:order-1"))
                .isInstanceOf(InsufficientBalanceException.class);

        verify(ledgerEntries, never()).save(any());
    }

    @Test
    void lockAgainstAnAssetTheUserHasNeverTouchedFailsAsInsufficientNotMissing() {
        when(balances.findForUpdate(USER_ID, ASSET_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> balanceService.lock(USER_ID, ASSET_ID, 1L,
                ReferenceType.ORDER, "order-1", "lock:order-1"))
                .isInstanceOf(InsufficientBalanceException.class);
    }

    @Test
    void replayingTheSameIdempotencyKeyMovesTheBalanceOnlyOnce() {
        Balance seed = new Balance(USER_ID, ASSET_ID);
        seed.credit(1_000L);
        when(balances.findForUpdate(USER_ID, ASSET_ID)).thenReturn(Optional.of(seed));
        when(balances.findByUserIdAndAssetId(USER_ID, ASSET_ID)).thenReturn(Optional.of(seed));

        balanceService.lock(USER_ID, ASSET_ID, 400L, ReferenceType.ORDER, "order-1", "lock:order-1");

        // The second delivery of the same event: the ledger already has this key. What
        // it returns doesn't matter -- mutate() only checks presence -- so a bare mock
        // stands in for it.
        when(ledgerEntries.findByIdempotencyKey("lock:order-1"))
                .thenReturn(Optional.of(mock(LedgerEntry.class)));

        Balance result = balanceService.lock(USER_ID, ASSET_ID, 400L,
                ReferenceType.ORDER, "order-1", "lock:order-1");

        assertThat(result.getAvailable()).isEqualTo(600L); // unchanged by the replay
        assertThat(result.getLocked()).isEqualTo(400L);
        verify(ledgerEntries, times(1)).save(any(LedgerEntry.class)); // only the first call saved
        verify(balances, times(1)).findForUpdate(USER_ID, ASSET_ID); // replay never re-reads for-update
    }

    @Test
    void applyBatchAppliesEveryMovementInOrderAgainstOneLoadedBalance() {
        Balance seed = new Balance(USER_ID, ASSET_ID);
        seed.credit(1_000L);
        seed.lock(400L);
        when(balances.findForUpdate(USER_ID, ASSET_ID)).thenReturn(Optional.of(seed));
        when(ledgerEntries.findExistingIdempotencyKeys(any())).thenReturn(List.of());

        balanceService.applyBatch(USER_ID, ASSET_ID, List.of(
                new BalanceService.PendingMovement(BalanceService.MovementKind.SETTLE_LOCKED, 100L,
                        ReferenceType.TRADE, "trade-1", "settle:trade-1"),
                new BalanceService.PendingMovement(BalanceService.MovementKind.CREDIT, 50L,
                        ReferenceType.TRADE, "trade-1", "credit:trade-1"),
                new BalanceService.PendingMovement(BalanceService.MovementKind.DEBIT, 5L,
                        ReferenceType.TRADE, "trade-1", "fee:trade-1"),
                new BalanceService.PendingMovement(BalanceService.MovementKind.REBATE, 3L,
                        ReferenceType.TRADE, "trade-2", "rebate:trade-2"),
                new BalanceService.PendingMovement(BalanceService.MovementKind.UNLOCK, 100L,
                        ReferenceType.ORDER, "order-1", "unlock:order-1")));

        // 600 available (1000 - 400 locked) +50 credit -5 fee +3 rebate, then unlock
        // moves 100 locked back to available.
        assertThat(seed.getAvailable()).isEqualTo(748L);
        assertThat(seed.getLocked()).isEqualTo(200L); // 400 - 100 settled - 100 unlocked
        verify(balances).save(seed);

        List<LedgerEntry> entries = captureSavedEntries();
        assertThat(entries).hasSize(5);
        assertThat(entries.get(0).getEntryType()).isEqualTo(LedgerEntryType.TRADE_DEBIT);
        assertThat(entries.get(0).getAmount()).isEqualTo(-100L);
        assertThat(entries.get(0).getBalanceAfter()).isEqualTo(300L); // locked after this step
        assertThat(entries.get(1).getBalanceAfter()).isEqualTo(650L); // available after credit
        assertThat(entries.get(2).getBalanceAfter()).isEqualTo(645L); // available after fee
        assertThat(entries.get(3).getEntryType()).isEqualTo(LedgerEntryType.REBATE);
        assertThat(entries.get(3).getBalanceAfter()).isEqualTo(648L);
        assertThat(entries.get(4).getEntryType()).isEqualTo(LedgerEntryType.ORDER_UNLOCK);
        assertThat(entries.get(4).getBalanceAfter()).isEqualTo(748L); // available after unlock
    }

    @Test
    void applyBatchSkipsAlreadyAppliedMovementsButAppliesTheRest() {
        Balance seed = new Balance(USER_ID, ASSET_ID);
        when(balances.findForUpdate(USER_ID, ASSET_ID)).thenReturn(Optional.of(seed));
        when(ledgerEntries.findExistingIdempotencyKeys(any())).thenReturn(List.of("credit:trade-1"));

        balanceService.applyBatch(USER_ID, ASSET_ID, List.of(
                new BalanceService.PendingMovement(BalanceService.MovementKind.CREDIT, 100L,
                        ReferenceType.TRADE, "trade-1", "credit:trade-1"),
                new BalanceService.PendingMovement(BalanceService.MovementKind.CREDIT, 50L,
                        ReferenceType.TRADE, "trade-2", "credit:trade-2")));

        assertThat(seed.getAvailable()).isEqualTo(50L); // only the non-skipped movement applied
        List<LedgerEntry> entries = captureSavedEntries();
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getIdempotencyKey()).isEqualTo("credit:trade-2");
    }

    @Test
    void applyBatchWhenEveryMovementIsAlreadyApplied_isACompleteNoOp() {
        when(ledgerEntries.findExistingIdempotencyKeys(any())).thenReturn(List.of("credit:trade-1"));

        balanceService.applyBatch(USER_ID, ASSET_ID, List.of(
                new BalanceService.PendingMovement(BalanceService.MovementKind.CREDIT, 100L,
                        ReferenceType.TRADE, "trade-1", "credit:trade-1")));

        verify(balances, never()).findForUpdate(any(), any());
        verify(balances, never()).save(any());
        verify(ledgerEntries, never()).saveAll(any());
    }

    @Test
    void applyBatchOnFirstEverTouchCreatesTheRow() {
        when(balances.findForUpdate(USER_ID, ASSET_ID)).thenReturn(Optional.empty());
        when(ledgerEntries.findExistingIdempotencyKeys(any())).thenReturn(List.of());

        balanceService.applyBatch(USER_ID, ASSET_ID, List.of(
                new BalanceService.PendingMovement(BalanceService.MovementKind.CREDIT, 500L,
                        ReferenceType.DEPOSIT, "deposit-1", "credit:deposit-1")));

        ArgumentCaptor<Balance> captor = ArgumentCaptor.forClass(Balance.class);
        verify(balances).save(captor.capture());
        assertThat(captor.getValue().getAvailable()).isEqualTo(500L);
    }

    @Test
    void applyBatchInsufficientBalanceThrowsAndWritesNothing() {
        Balance seed = new Balance(USER_ID, ASSET_ID);
        seed.credit(10L);
        when(balances.findForUpdate(USER_ID, ASSET_ID)).thenReturn(Optional.of(seed));
        when(ledgerEntries.findExistingIdempotencyKeys(any())).thenReturn(List.of());

        assertThatThrownBy(() -> balanceService.applyBatch(USER_ID, ASSET_ID, List.of(
                new BalanceService.PendingMovement(BalanceService.MovementKind.DEBIT, 100L,
                        ReferenceType.TRADE, "trade-1", "fee:trade-1"))))
                .isInstanceOf(InsufficientBalanceException.class);

        verify(balances, never()).save(any());
        verify(ledgerEntries, never()).saveAll(any());
    }

    private List<LedgerEntry> captureSavedEntries() {
        ArgumentCaptor<List<LedgerEntry>> captor = ArgumentCaptor.forClass(List.class);
        verify(ledgerEntries).saveAll(captor.capture());
        return captor.getValue();
    }

    private LedgerEntry captureSavedEntry() {
        ArgumentCaptor<LedgerEntry> captor = ArgumentCaptor.forClass(LedgerEntry.class);
        verify(ledgerEntries).save(captor.capture());
        return captor.getValue();
    }

    /**
     * Every movement announces the balance it produced. Not decoration: this is the only
     * place that cannot be bypassed, and the reason the producer lives in the writer rather
     * than in each of the four apps that call it.
     */
    @Test
    void everyMovementAnnouncesTheBalanceItProduced() {
        Balance seed = new Balance(USER_ID, ASSET_ID);
        seed.credit(1_000L);
        when(balances.findForUpdate(USER_ID, ASSET_ID)).thenReturn(Optional.of(seed));

        balanceService.lock(USER_ID, ASSET_ID, 400L, ReferenceType.ORDER, "o1", "k1");

        verify(changes).publishAfterCommit(seed);
    }

    /**
     * A redelivery announces nothing, because nothing changed. Announcing anyway would be
     * harmless for a client applying absolute balances, and misleading for anyone reading the
     * topic to see what the exchange did.
     */
    @Test
    void areplayedMovementAnnouncesNothing() {
        when(ledgerEntries.findByIdempotencyKey("k1"))
                .thenReturn(Optional.of(mock(LedgerEntry.class)));
        when(balances.findByUserIdAndAssetId(USER_ID, ASSET_ID))
                .thenReturn(Optional.of(new Balance(USER_ID, ASSET_ID)));

        balanceService.lock(USER_ID, ASSET_ID, 400L, ReferenceType.ORDER, "o1", "k1");

        verify(changes, never()).publishAfterCommit(any());
    }

    /**
     * Once per batch, not once per movement. A fill settles the lock, credits the proceeds and
     * takes the fee -- three states each true for microseconds. Announcing all three would
     * make a balance panel flicker through values nobody held long enough to see.
     */
    @Test
    void abatchAnnouncesOnceNotPerMovement() {
        Balance seed = new Balance(USER_ID, ASSET_ID);
        seed.credit(1_000L);
        seed.lock(500L);
        when(balances.findForUpdate(USER_ID, ASSET_ID)).thenReturn(Optional.of(seed));
        when(ledgerEntries.findExistingIdempotencyKeys(any())).thenReturn(List.of());

        balanceService.applyBatch(USER_ID, ASSET_ID, List.of(
                new BalanceService.PendingMovement(BalanceService.MovementKind.SETTLE_LOCKED,
                        500L, ReferenceType.TRADE, "t1", "k1"),
                new BalanceService.PendingMovement(BalanceService.MovementKind.CREDIT,
                        200L, ReferenceType.TRADE, "t1", "k2"),
                new BalanceService.PendingMovement(BalanceService.MovementKind.DEBIT,
                        10L, ReferenceType.TRADE, "t1", "k3")));

        verify(changes, times(1)).publishAfterCommit(seed);
    }

    /** A fully-redelivered batch writes nothing, so it announces nothing either. */
    @Test
    void afullyReplayedBatchAnnouncesNothing() {
        when(ledgerEntries.findExistingIdempotencyKeys(any())).thenReturn(List.of("k1"));

        balanceService.applyBatch(USER_ID, ASSET_ID, List.of(
                new BalanceService.PendingMovement(BalanceService.MovementKind.CREDIT,
                        200L, ReferenceType.TRADE, "t1", "k1")));

        verify(changes, never()).publishAfterCommit(any());
    }
}
