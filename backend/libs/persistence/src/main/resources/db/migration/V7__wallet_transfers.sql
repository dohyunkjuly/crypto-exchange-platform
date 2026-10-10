-- A move of available funds between two accounts of one family: master to sub, sub to
-- master, sub to sub. Instant, one transaction, ledgered TRANSFER on both sides. The row is
-- the history and the idempotency: a retried transfer with the same id is the same transfer.
-- docs/reference/sub-accounts.md.
CREATE TABLE wallet_transfers (
    id              BINARY(16)    NOT NULL PRIMARY KEY,
    from_user_id    BIGINT        NOT NULL,
    to_user_id      BIGINT        NOT NULL,
    asset_id        BIGINT        NOT NULL,
    amount          BIGINT        NOT NULL,
    -- The master who asked for it, which may be neither side.
    requested_by    BIGINT        NOT NULL,
    created_at      TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    INDEX idx_wallet_transfers_from (from_user_id, created_at),
    INDEX idx_wallet_transfers_to (to_user_id, created_at),
    INDEX idx_wallet_transfers_requested_by (requested_by, created_at)
);
