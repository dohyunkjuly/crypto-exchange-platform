package com.exchange.copy.follow;

/** How a copied buy is sized. A sell, in either, sells the fraction of the holding the leader sold. */
public enum CopyMode {
    /** The same fraction of available USDT the leader's order used. */
    FIXED_RATIO,
    /** The follower's own amount per order, or what is left if that is less. */
    FIXED_AMOUNT
}
