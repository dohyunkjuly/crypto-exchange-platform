package com.exchange.user.session;

public enum SessionRevokedReason {
    LOGOUT,
    EXPIRED,
    PASSWORD_CHANGED,
    REVOKED_BY_USER,
    REVOKED_BY_ADMIN,
    SUSPICIOUS_ACTIVITY
}
