package com.exchange.admin.session;

/** PENDING between the password and the second factor; only ACTIVE opens anything. */
public enum AdminSessionState {
    PENDING,
    ACTIVE
}
