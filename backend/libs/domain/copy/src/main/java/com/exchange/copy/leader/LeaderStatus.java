package com.exchange.copy.leader;

public enum LeaderStatus {
    /** No longer written: staff approval was removed (V27). Kept because a stored value is never removed. */
    APPLIED,
    ACTIVE,
    /** By staff: no new followers, and the copy accounts are closed. */
    SUSPENDED,
    /** No longer written, as {@link #APPLIED}. */
    REJECTED,
    /** By the leader. */
    CLOSED
}
