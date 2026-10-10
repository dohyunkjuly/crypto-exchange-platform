package com.exchange.user.identity;

public enum UserType {
    PRIMARY,
    SUB,
    /**
     * The exchange's own books: {@code fees@exchange}, {@code treasury@exchange}. An ordinary
     * row so the ledger and the balance register work on it unchanged, and a distinct type so
     * nothing that serves a customer can ever reach it -- it never logs in and never trades,
     * by rule rather than by what its columns happen to hold. docs/reference/treasury.md.
     */
    SYSTEM
}
