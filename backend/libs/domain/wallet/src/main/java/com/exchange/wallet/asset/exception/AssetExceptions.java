package com.exchange.wallet.asset.exception;

/**
 * Every way assets can be refused, in one place. Plain exceptions with no error
 * code: each app maps them to its own (docs/reference/error-codes.md). One file so the full
 * list reads at a glance; one class each so an advice can still dispatch on the type.
 */
public final class AssetExceptions {

    private AssetExceptions() {
    }

    /** No asset matches the given symbol or id. */
    public static class AssetNotFoundException extends RuntimeException {

        public AssetNotFoundException(Object identifier) {
            super("No such asset: " + identifier);
        }
    }
}
