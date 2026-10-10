package com.exchange.wallet.deposit;

public enum DepositStatus {
    /** Seen on-chain, not yet buried deep enough. */
    DETECTED,
    /** In a block, accumulating confirmations. */
    CONFIRMING,
    /** Balance moved. Terminal. */
    CREDITED,
    /** The block was reorged away. */
    ORPHANED,
    /** Refused by AML screening or a blocked source. */
    REJECTED,
    FAILED
}
