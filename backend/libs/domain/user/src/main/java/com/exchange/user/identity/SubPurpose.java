package com.exchange.user.identity;

/**
 * What a sub-account is for (docs/reference/copy-trading.md). Only a {@link UserType#SUB} is
 * ever anything but {@link #STANDARD}; the table's check constraint holds that.
 */
public enum SubPurpose {
    /** An ordinary sub-account, and what every master and system row carries. */
    STANDARD,
    /** A leader's portfolio: the one account whose orders are copied. */
    LEAD,
    /** A follower's copy of one leader. Only the copier places orders on it. */
    COPY
}
