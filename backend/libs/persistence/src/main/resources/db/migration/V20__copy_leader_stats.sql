-- What a leader's profile shows (docs/reference/copy-trading.md), written by the nightly job in
-- spot-copy and read by web-bff. Derived: every row here can be thrown away and the profile
-- loses its history, nothing else.

-- The lead account at the end of a UTC day, valued in the copy asset at each market's last
-- trade. A return is the chain of one snapshot to the next with the money moved in or out
-- between them taken off, so funding a lead account is not a profit.
CREATE TABLE copy_leader_snapshots (
    leader_id      BINARY(16)   NOT NULL,
    day            DATE         NOT NULL,
    equity         BIGINT       NOT NULL,
    -- In minus out since the snapshot before, valued the same way.
    net_transfers  BIGINT       NOT NULL,
    taken_at       TIMESTAMP(6) NOT NULL,

    PRIMARY KEY (leader_id, day),
    CONSTRAINT ck_copy_leader_snapshots_equity CHECK (equity >= 0)
);

-- One row a leader, replaced each night. A return is NULL until there are two snapshots to
-- compare; the success rate is NULL until a follower's copy has had an outcome.
CREATE TABLE copy_leader_stats (
    leader_id         BINARY(16)   NOT NULL PRIMARY KEY,
    -- Over the last 7, 30 and 90 days: the return, what was earned in the copy asset, and the
    -- deepest fall from a high.
    return_7d_bps         INT      NULL,
    return_30d_bps        INT      NULL,
    return_90d_bps        INT      NULL,
    pnl_7d                BIGINT   NULL,
    pnl_30d               BIGINT   NULL,
    pnl_90d               BIGINT   NULL,
    max_drawdown_7d_bps   INT      NULL,
    max_drawdown_30d_bps  INT      NULL,
    max_drawdown_90d_bps  INT      NULL,
    followers         INT          NOT NULL,
    -- What the active copy accounts hold, valued in the copy asset.
    funds_copying     BIGINT       NOT NULL,
    -- Of the last thirty days' copies that had an outcome, how many filled.
    copy_success_bps  INT          NULL,
    copies_counted    INT          NOT NULL,
    computed_at       TIMESTAMP(6) NOT NULL
);
