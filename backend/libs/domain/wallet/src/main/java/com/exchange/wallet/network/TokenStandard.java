package com.exchange.wallet.network;

public enum TokenStandard {
    /** The chain's own coin — ETH on Ethereum, TRX on Tron. No contract address. */
    NATIVE,
    ERC20,
    TRC20,
    BEP20,
    SPL
}
