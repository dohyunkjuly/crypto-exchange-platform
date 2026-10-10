package com.exchange.wallet.balance;

import com.exchange.wallet.balance.exception.InsufficientBalanceException;
import com.exchange.wallet.ledger.LedgerEntry;
import com.exchange.wallet.ledger.LedgerEntryRepository;
import com.exchange.wallet.ledger.LedgerEntryType;
import com.exchange.wallet.ledger.ReferenceType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.ObjLongConsumer;
import java.util.function.ToLongFunction;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every movement of available/locked funds, each paired with the {@link LedgerEntry} that
 * explains it.
 *
 * <p><b>Every change here is announced</b> after commit ({@link BalanceChangePublisher}) -- in
 * this class rather than in each caller, because it is the only place that cannot be bypassed.
 * It costs nothing on the path: built and sent on another thread, and dropped on failure.
 *
 * <p>Two independently-auditable registers. {@code lock}/{@code unlock}/{@code credit}/
 * {@code debit} record the signed delta to {@code available}; {@code settleLocked} records it
 * against {@code locked}, each snapshotting its own side in {@code balanceAfter}. A lock/unlock
 * pair nets to zero and is still ledgered on each side, because "why is available lower right
 * now" is the question this exists to answer.
 */
@Service
public class BalanceService {

    private final BalanceRepository balances;
    private final LedgerEntryRepository ledgerEntries;
    private final BalanceChangePublisher changes;

    public BalanceService(BalanceRepository balances, LedgerEntryRepository ledgerEntries,
                          BalanceChangePublisher changes) {
        this.balances = balances;
        this.ledgerEntries = ledgerEntries;
        this.changes = changes;
    }

    /** The portfolio screen. */
    public List<Balance> list(Long userId) {
        return balances.findByUserId(userId);
    }

    /** Several accounts at once; the caller decides whose ids it is allowed to ask for. */
    public List<Balance> list(Collection<Long> userIds) {
        return userIds.isEmpty() ? List.of() : balances.findByUserIdIn(userIds);
    }

    /**
     * Reserving funds for an order.
     *
     * @throws InsufficientBalanceException available is short
     */
    @Transactional
    public Balance lock(Long userId, Long assetId, long amount,
                        ReferenceType referenceType, String referenceId, String idempotencyKey) {
        return mutate(userId, assetId, amount, MovementKind.LOCK, referenceType, referenceId,
                idempotencyKey);
    }

    /** Cancelling or expiring an order. */
    @Transactional
    public Balance unlock(Long userId, Long assetId, long amount,
                          ReferenceType referenceType, String referenceId, String idempotencyKey) {
        return mutate(userId, assetId, amount, MovementKind.UNLOCK, referenceType, referenceId,
                idempotencyKey);
    }

    /** A fill: the locked funds actually leave. */
    @Transactional
    public Balance settleLocked(Long userId, Long assetId, long amount,
                                ReferenceType referenceType, String referenceId, String idempotencyKey) {
        return mutate(userId, assetId, amount, MovementKind.SETTLE_LOCKED, referenceType,
                referenceId, idempotencyKey);
    }

    /** Trade proceeds. Ledgered as TRADE_CREDIT, so nothing else may use it: money arriving from
     *  outside is {@link #deposit}, a transfer is {@link #transferIn}. */
    @Transactional
    public Balance credit(Long userId, Long assetId, long amount,
                          ReferenceType referenceType, String referenceId, String idempotencyKey) {
        return mutate(userId, assetId, amount, MovementKind.CREDIT, referenceType, referenceId,
                idempotencyKey);
    }

    /**
     * A fee, spent directly rather than through a lock -- and ledgered as one, so nothing else
     * may use it: a transfer is {@link #transferOut}, a correction is an adjustment.
     *
     * @throws InsufficientBalanceException available is short
     */
    @Transactional
    public Balance debit(Long userId, Long assetId, long amount,
                         ReferenceType referenceType, String referenceId, String idempotencyKey) {
        return mutate(userId, assetId, amount, MovementKind.DEBIT, referenceType, referenceId,
                idempotencyKey);
    }

    /**
     * Funds arriving from outside the exchange. The dev faucet stands in for a deposit and is
     * ledgered as one; before this it was a TRADE_CREDIT, which is what the fee sweep's cousin,
     * a trade-volume report, would have counted.
     */
    @Transactional
    public Balance deposit(Long userId, Long assetId, long amount,
                           ReferenceType referenceType, String referenceId, String idempotencyKey) {
        return mutate(userId, assetId, amount, MovementKind.DEPOSIT, referenceType, referenceId,
                idempotencyKey);
    }

    /**
     * The two halves of a transfer between accounts, ledgered as what they are. {@link #debit}
     * would record the outgoing side as a FEE -- and the fee sweep then reads it as revenue
     * (docs/reference/treasury.md), which is how 40 USDT of a customer's own transfer once
     * reached the house. The ledger's type is what every reader keys on; it must say TRANSFER.
     */
    @Transactional
    public Balance transferOut(Long userId, Long assetId, long amount,
                               ReferenceType referenceType, String referenceId, String idempotencyKey) {
        return mutate(userId, assetId, amount, MovementKind.TRANSFER_OUT, referenceType, referenceId,
                idempotencyKey);
    }

    @Transactional
    public Balance transferIn(Long userId, Long assetId, long amount,
                              ReferenceType referenceType, String referenceId, String idempotencyKey) {
        return mutate(userId, assetId, amount, MovementKind.TRANSFER_IN, referenceType, referenceId,
                idempotencyKey);
    }

    /**
     * The two halves of copy trading's profit share, ledgered as what they are
     * (docs/reference/copy-trading.md): a transfer the customer did not ask for, and no fee of
     * the exchange's.
     *
     * @throws InsufficientBalanceException the copy account holds less than is being paid
     */
    @Transactional
    public Balance payProfitShare(Long userId, Long assetId, long amount, String referenceId, String idempotencyKey) {
        return mutate(userId, assetId, amount, MovementKind.PROFIT_SHARE_OUT, ReferenceType.PROFIT_SHARE,
                referenceId, idempotencyKey);
    }

    @Transactional
    public Balance receiveProfitShare(Long userId, Long assetId, long amount, String referenceId,
                                      String idempotencyKey) {
        return mutate(userId, assetId, amount, MovementKind.PROFIT_SHARE_IN, ReferenceType.PROFIT_SHARE,
                referenceId, idempotencyKey);
    }

    /** Fee revenue into the exchange's own account (docs/reference/treasury.md). */
    @Transactional
    public Balance sweepFees(Long userId, Long assetId, long amount,
                             ReferenceType referenceType, String referenceId, String idempotencyKey) {
        return mutate(userId, assetId, amount, MovementKind.FEE_SWEEP, referenceType, referenceId,
                idempotencyKey);
    }

    /**
     * A negative fee -- a maker rebate. Economically the same movement as {@link
     * #credit}, funds landing in {@code available}; kept as its own method purely so
     * the ledger records {@link LedgerEntryType#REBATE} rather than {@code
     * TRADE_CREDIT}, so a rebate stays distinguishable from ordinary trade proceeds on
     * later review.
     */
    @Transactional
    public Balance rebate(Long userId, Long assetId, long amount,
                          ReferenceType referenceType, String referenceId, String idempotencyKey) {
        return mutate(userId, assetId, amount, MovementKind.REBATE, referenceType, referenceId,
                idempotencyKey);
    }

    /**
     * Loads the balance -- creating a zero row if this is the user's first-ever touch
     * of the asset, so {@code lock}/{@code debit} against it fail with {@link
     * InsufficientBalanceException} exactly as they would against an existing empty
     * row, no special-casing needed -- applies {@code kind}'s mutation, saves it, and
     * writes the {@link LedgerEntry} it implies.
     *
     * <p>Idempotent: replaying the same {@code idempotencyKey} (a Kafka redelivery) is
     * a no-op that returns the balance unchanged. The ledger's unique index on {@code
     * idempotencyKey} is what makes this safe to call twice for the same event.
     */
    private Balance mutate(Long userId, Long assetId, long amount, MovementKind kind,
                           ReferenceType referenceType, String referenceId, String idempotencyKey) {
        Optional<LedgerEntry> alreadyApplied = ledgerEntries.findByIdempotencyKey(idempotencyKey);
        if (alreadyApplied.isPresent()) {
            return balances.findByUserIdAndAssetId(userId, assetId).orElseThrow();
        }
        Balance balance = balances.findForUpdate(userId, assetId)
                .orElseGet(() -> new Balance(userId, assetId));
        kind.mutation.accept(balance, amount);
        balance = balances.save(balance);
        ledgerEntries.save(new LedgerEntry(userId, assetId, kind.entryType, kind.signedAmount(amount),
                kind.balanceAfterField.applyAsLong(balance), referenceType, referenceId, idempotencyKey));
        changes.publishAfterCommit(balance);
        return balance;
    }

    /**
     * One movement to apply as part of a batch, destined for {@link #applyBatch} rather
     * than one of the single-movement methods above -- same information those take,
     * just described instead of applied immediately, so many of them (from however many
     * trades in a settlement batch touched one balance row) can be collected first and
     * applied together.
     */
    public record PendingMovement(MovementKind kind, long amount, ReferenceType referenceType,
                                  String referenceId, String idempotencyKey) {
    }

    /**
     * Applies every movement against ONE {@code (userId, assetId)} balance -- what calling
     * {@link #lock}/{@link #credit}/etc. per movement would do, without a round trip between
     * each. The case it exists for: a taker fragmenting one order across many makers sees one
     * movement per fill, so a large order can mean thousands against the same row.
     *
     * <p>Locks the row once, then applies every movement whose {@code idempotencyKey} is not
     * already recorded -- checked in bulk, not per movement -- to that one loaded instance, in
     * order, in Java. Not a different algorithm: {@link Balance}'s own methods are reused, so
     * every invariant still throws and each {@link LedgerEntry} still gets an exact, correctly
     * ordered {@code balanceAfter}, since the same object is mutated step by step before
     * anything is written back.
     *
     * <p>A fully redelivered batch is a complete no-op: no balance write, no ledger rows.
     */
    @Transactional
    public void applyBatch(Long userId, Long assetId, List<PendingMovement> movements) {
        List<String> keys = movements.stream().map(PendingMovement::idempotencyKey).toList();
        Set<String> alreadyApplied = new HashSet<>(ledgerEntries.findExistingIdempotencyKeys(keys));
        List<PendingMovement> newMovements = movements.stream()
                .filter(m -> !alreadyApplied.contains(m.idempotencyKey()))
                .toList();
        if (newMovements.isEmpty()) {
            return;
        }

        Balance balance = balances.findForUpdate(userId, assetId)
                .orElseGet(() -> new Balance(userId, assetId));
        List<LedgerEntry> entries = new ArrayList<>(newMovements.size());
        for (PendingMovement m : newMovements) {
            m.kind().mutation.accept(balance, m.amount());
            entries.add(new LedgerEntry(userId, assetId, m.kind().entryType,
                    m.kind().signedAmount(m.amount()), m.kind().balanceAfterField.applyAsLong(balance),
                    m.referenceType(), m.referenceId(), m.idempotencyKey()));
        }
        balances.save(balance);
        ledgerEntries.saveAll(entries);
        // Once for the batch, not once per movement. The intermediate states a trade passes
        // through -- settle the lock, credit the proceeds, take the fee -- were each true for
        // microseconds and nobody wants to watch a panel flicker through them.
        changes.publishAfterCommit(balance);
    }

    /**
     * Which {@link Balance} method a movement applies, which field its {@link
     * LedgerEntry} snapshots as {@code balanceAfter}, which {@link LedgerEntryType} it
     * is ledgered under, and which sign {@code amount} carries into the ledger -- the
     * one place all four are decided, used by both {@link #mutate} (the single-movement
     * path) and {@link #applyBatch}, so a batched movement is ledgered under exactly
     * the same rules as one applied on its own.
     */
    public enum MovementKind {
        LOCK(Balance::lock, Balance::getAvailable, LedgerEntryType.ORDER_LOCK, false),
        UNLOCK(Balance::unlock, Balance::getAvailable, LedgerEntryType.ORDER_UNLOCK, true),
        SETTLE_LOCKED(Balance::settleLocked, Balance::getLocked, LedgerEntryType.TRADE_DEBIT, false),
        CREDIT(Balance::credit, Balance::getAvailable, LedgerEntryType.TRADE_CREDIT, true),
        DEBIT(Balance::debit, Balance::getAvailable, LedgerEntryType.FEE, false),
        REBATE(Balance::credit, Balance::getAvailable, LedgerEntryType.REBATE, true),
        /** Only the fee account's own credits, so they are never read as trade proceeds. */
        FEE_SWEEP(Balance::credit, Balance::getAvailable, LedgerEntryType.FEE_SWEEP, true),
        TRANSFER_OUT(Balance::debit, Balance::getAvailable, LedgerEntryType.TRANSFER_OUT, false),
        TRANSFER_IN(Balance::credit, Balance::getAvailable, LedgerEntryType.TRANSFER_IN, true),
        DEPOSIT(Balance::credit, Balance::getAvailable, LedgerEntryType.DEPOSIT, true),
        PROFIT_SHARE_OUT(Balance::debit, Balance::getAvailable, LedgerEntryType.PROFIT_SHARE_OUT, false),
        PROFIT_SHARE_IN(Balance::credit, Balance::getAvailable, LedgerEntryType.PROFIT_SHARE_IN, true);

        private final ObjLongConsumer<Balance> mutation;
        private final ToLongFunction<Balance> balanceAfterField;
        private final LedgerEntryType entryType;
        private final boolean positive;

        MovementKind(ObjLongConsumer<Balance> mutation, ToLongFunction<Balance> balanceAfterField,
                    LedgerEntryType entryType, boolean positive) {
            this.mutation = mutation;
            this.balanceAfterField = balanceAfterField;
            this.entryType = entryType;
            this.positive = positive;
        }

        private long signedAmount(long amount) {
            return positive ? amount : -amount;
        }
    }
}
