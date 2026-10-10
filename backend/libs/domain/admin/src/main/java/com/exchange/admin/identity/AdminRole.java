package com.exchange.admin.identity;

/** A floor, not a set of permissions: each role may do everything the one before it may. */
public enum AdminRole {
    VIEWER,
    OPERATOR,
    SUPERADMIN;

    public boolean atLeast(AdminRole needed) {
        return ordinal() >= needed.ordinal();
    }
}
