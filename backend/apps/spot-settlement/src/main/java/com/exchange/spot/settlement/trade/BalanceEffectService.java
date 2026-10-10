package com.exchange.spot.settlement.trade;

import com.exchange.wallet.balance.BalanceService;
import com.exchange.wallet.balance.BalanceService.MovementKind;
import com.exchange.wallet.balance.BalanceService.PendingMovement;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies every {@link BalanceMutation} in one {@code (userId, assetId)} group as a
 * SINGLE aggregate call to {@link BalanceService#applyBatch} -- not one {@code
 * BalanceService} call per mutation, the same reason {@code OrderEffectService}
 * aggregates one order's fills into one write: a repeated taker's own balance sees one
 * movement per trade, so a large order fragmenting into many fills against many
 * counterparties can mean thousands of mutations against the very same row, and each
 * one used to be its own round trip to the database. {@code applyBatch}'s own javadoc
 * covers why this changes nothing about correctness -- same {@link
 * com.exchange.wallet.balance.Balance} methods, same invariants, same one-{@code
 * LedgerEntry}-per-mutation audit fidelity, just without a round trip between each one.
 */
@Service
public class BalanceEffectService {

    private final BalanceService balanceService;

    public BalanceEffectService(BalanceService balanceService) {
        this.balanceService = balanceService;
    }

    @Transactional
    public void applyGroup(List<BalanceMutation> mutations) {
        if (mutations.isEmpty()) {
            return;
        }
        BalanceMutation first = mutations.get(0);
        List<PendingMovement> movements = mutations.stream()
                .map(BalanceEffectService::toPendingMovement)
                .toList();
        balanceService.applyBatch(first.userId(), first.assetId(), movements);
    }

    private static PendingMovement toPendingMovement(BalanceMutation m) {
        MovementKind kind = switch (m.kind()) {
            case SETTLE_LOCKED -> MovementKind.SETTLE_LOCKED;
            case CREDIT -> MovementKind.CREDIT;
            case DEBIT -> MovementKind.DEBIT;
            case REBATE -> MovementKind.REBATE;
            case UNLOCK -> MovementKind.UNLOCK;
        };
        return new PendingMovement(kind, m.amount(), m.referenceType(), m.referenceId(),
                m.idempotencyKey());
    }
}
