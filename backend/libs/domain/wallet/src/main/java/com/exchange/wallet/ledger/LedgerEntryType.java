package com.exchange.wallet.ledger;

/**
 * Why a balance moved.
 *
 * <p>Add freely — the column has no CHECK constraint, so a new value needs no migration.
 * Never rename or remove one: the name is what is on disk, and historical rows keep it.
 */
public enum LedgerEntryType {
    DEPOSIT,
    WITHDRAWAL,
    /** Reserving funds for an order: available down, locked up. Net zero. */
    ORDER_LOCK,
    /** Cancelling or expiring an order: locked back to available. Net zero. */
    ORDER_UNLOCK,
    TRADE_CREDIT,
    TRADE_DEBIT,
    FEE,
    /** Negative fee — what a maker earns for providing liquidity. */
    REBATE,
    TRANSFER_IN,
    TRANSFER_OUT,
    /** Manual correction. The only way to fix a mistake in an append-only ledger. */
    ADJUSTMENT,
    /**
     * Fee revenue reaching the exchange's own account, aggregated: one entry per asset per
     * sweep rather than one per trade. The per-trade record is the trader's own FEE row
     * (docs/reference/treasury.md).
     */
    FEE_SWEEP,
    /**
     * Copy trading's profit share (docs/reference/copy-trading.md): out of a copy account, into
     * the leader's own account. Its own pair so that neither reads as a trade, a fee or a transfer
     * the customer asked for.
     */
    PROFIT_SHARE_OUT,
    PROFIT_SHARE_IN
}
