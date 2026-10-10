package com.exchange.wallet.ledger;

public enum ReferenceType {
    ORDER,
    TRADE,
    DEPOSIT,
    WITHDRAWAL,
    TRANSFER,
    ADMIN,
    /** A copy_profit_shares row. */
    PROFIT_SHARE
}
