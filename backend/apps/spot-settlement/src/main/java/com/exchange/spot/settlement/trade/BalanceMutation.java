package com.exchange.spot.settlement.trade;

import com.exchange.wallet.ledger.ReferenceType;

/**
 * One pending {@code BalanceService} call, described rather than applied immediately --
 * so a whole {@code (userId, assetId)} group's worth of mutations, from however many
 * trades in a batch touched that one balance row, can be collected first and applied
 * together by {@link BalanceEffectService}. {@code amount} is always positive; {@link
 * Kind} says which direction and which ledger type it becomes, mirroring {@code
 * BalanceService}'s own methods exactly.
 */
public record BalanceMutation(Kind kind, Long userId, Long assetId, long amount,
                              ReferenceType referenceType, String referenceId,
                              String idempotencyKey) {

    public enum Kind {
        SETTLE_LOCKED, CREDIT, DEBIT, REBATE, UNLOCK
    }
}
