-- Copy trading's profit share (docs/reference/copy-trading.md): the leader's part of a
-- follower's realized profit, settled weekly, on closing, and when funds are taken out.

-- The rate a follower agreed to, frozen when they started: the share is cumulative over the
-- life of the copy account, and a rate that moved would bill profit already settled again.
-- Existing rows take the column's zero; none exist outside a developer's machine.
ALTER TABLE copy_follows
    ADD COLUMN profit_share_bps INT NOT NULL AFTER amount_per_order,
    ADD CONSTRAINT ck_copy_follows_profit_share_bps CHECK (profit_share_bps BETWEEN 0 AND 10000);

-- How far along the market's trades this position has been counted, so a settlement reads
-- only the fills since the one before.
ALTER TABLE copy_positions
    ADD COLUMN last_sequence BIGINT NOT NULL AFTER cost;

-- One settlement of one copy account. `due` is what the formula said was owed at that moment
-- and `paid` what the account could pay; the difference stays owed, and the next settlement
-- finds it again in the same formula. A weekly row is unique for its week, so a job run twice
-- settles once; a closing or a withdrawal settles whatever is due then.
CREATE TABLE copy_profit_shares (
    id               BINARY(16)   NOT NULL PRIMARY KEY,
    follow_id        BINARY(16)   NOT NULL,
    trigger_kind     VARCHAR(16)  NOT NULL,
    -- The Monday the week ended on, for WEEKLY; NULL otherwise.
    week             DATE         NULL,
    rate_bps         INT          NOT NULL,
    -- The follower's realized profit at that moment, net of fees, signed.
    realized_profit  BIGINT       NOT NULL,
    due              BIGINT       NOT NULL,
    paid             BIGINT       NOT NULL,
    created_at       TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT uq_copy_profit_shares_week UNIQUE (follow_id, week),
    CONSTRAINT ck_copy_profit_shares_trigger CHECK (trigger_kind IN ('WEEKLY', 'CLOSE', 'WITHDRAWAL')),
    CONSTRAINT ck_copy_profit_shares_amounts CHECK (due >= 0 AND paid >= 0 AND paid <= due),
    INDEX idx_copy_profit_shares_follow (follow_id, created_at)
) ENGINE = InnoDB;
