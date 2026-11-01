package com.exchange.wallet.balance.exception;

/**
 * Every way balances can be refused, in one place. Plain exceptions with no error
 * code: each app maps them to its own (docs/reference/error-codes.md). One file so the full
 * list reads at a glance; one class each so an advice can still dispatch on the type.
 */
public final class BalanceExceptions {

    private BalanceExceptions() {
    }

    /**
     * Thrown rather than returning false, because every caller must handle it and a boolean is
     * too easy to ignore. Carries the numbers so the log line is useful without a second query.
     */
    public static class InsufficientBalanceException extends RuntimeException {

        private final Long userId;
        private final Long assetId;
        private final long requested;
        private final long available;

        public InsufficientBalanceException(Long userId, Long assetId, long requested, long available) {
            super("user " + userId + " asset " + assetId
                    + ": requested " + requested + ", available " + available);
            this.userId = userId;
            this.assetId = assetId;
            this.requested = requested;
            this.available = available;
        }

        public Long getUserId() {
            return userId;
        }

        public Long getAssetId() {
            return assetId;
        }

        public long getRequested() {
            return requested;
        }

        public long getAvailable() {
            return available;
        }
    }
}
