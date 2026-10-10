package com.exchange.copy.follow;

public enum CloseReason {
    /** By the follower. */
    STOPPED,
    /** Failed copies in a row reached the limit. */
    FAILURES,
    LEADER_CLOSED,
    LEADER_SUSPENDED
}
