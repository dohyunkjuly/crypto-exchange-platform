package com.exchange.wallet.withdrawal;

public enum WithdrawalStatus {
    /** Created; funds locked but not yet gone. */
    REQUESTED,
    /** Waiting on a human. */
    PENDING_APPROVAL,
    APPROVED,
    /** Handed to the signer. */
    BROADCASTING,
    /** On the chain, not yet confirmed. */
    BROADCAST,
    /** Terminal, and irreversible. */
    CONFIRMED,
    REJECTED,
    /** Withdrawn by the user before broadcast. */
    CANCELLED,
    /** Broadcast failed; funds returned. */
    FAILED
}
