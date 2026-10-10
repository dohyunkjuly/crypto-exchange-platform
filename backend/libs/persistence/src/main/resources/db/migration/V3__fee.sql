-- The fee domain: the published schedule per product, each user's volume and tier, and
-- negotiated overrides. Its own domain rather than part of spot because spot and futures are
-- separate ladders, and a user's level is earned separately on each.

-- The published fee schedule. Small -- a dozen rows per product -- and read constantly,
-- so it is the obvious thing to cache. OrderService resolves every order against the
-- level-0 row and refuses to start without one: a ladder with no bottom rung is not a
-- configuration, so the schedule is seeded (db/seed) rather than shipped in the schema.
--
-- `product` is why this table is not in the spot domain. Spot and futures are separate
-- ladders, not a discount on one ladder: spot's entry tier is typically 10/10 bps where
-- USD-M futures' is 2/5. A user can be tier 3 on spot and tier 0 on futures, because the
-- volume that earns each is counted separately.
--
-- Signed basis points. 1 bps = 0.01%, so 0.1% is 10. A negative maker fee is a rebate --
-- what an exchange pays a market maker to keep the book tight -- which is why these are
-- not UNSIGNED.

CREATE TABLE fee_tiers (
    id                  BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,

    product             VARCHAR(16)  NOT NULL,
    level               INT          NOT NULL,
    name                VARCHAR(32)  NOT NULL,

    -- Trailing 30-day volume required, in the quote asset, scaled by 1e8. The lowest tier
    -- is 0 and everyone starts there.
    min_30d_volume      BIGINT       NOT NULL,

    -- Some exchanges also require holding their token to reach a tier. Nullable because
    -- most tiers do not. Names a wallet asset without a foreign key: fee and wallet are
    -- different domains.
    min_asset_id        BIGINT       NULL,
    min_asset_balance   BIGINT       NULL,

    maker_fee_bps       INT          NOT NULL,
    taker_fee_bps       INT          NOT NULL,

    -- Schedules get republished. Rather than editing a row and losing what people were
    -- actually charged last quarter, close the old one and insert a new one.
    effective_from      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    effective_to        TIMESTAMP(6) NULL,

    created_at          TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                     ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT uq_fee_tiers_product_level_from UNIQUE (product, level, effective_from),

    CONSTRAINT ck_fee_tiers_product CHECK (
        product IN ('SPOT', 'USDM_FUTURES', 'COINM_FUTURES', 'MARGIN')
    ),
    CONSTRAINT ck_fee_tiers_level CHECK (level >= 0),
    CONSTRAINT ck_fee_tiers_volume CHECK (min_30d_volume >= 0),
    CONSTRAINT ck_fee_tiers_rates CHECK (
        maker_fee_bps > -10000 AND maker_fee_bps < 10000
        AND taker_fee_bps > -10000 AND taker_fee_bps < 10000
    )
) ENGINE = InnoDB;

-- Resolving a tier: walk this product's ladder in volume order.
CREATE INDEX idx_fee_tiers_product_volume ON fee_tiers (product, min_30d_volume);

-- One row per user per day per product. The table that makes tiering affordable.
--
-- Without it, "what is this user's 30-day volume" is an aggregate over spot_trades --
-- millions of rows, and the tier job would have to do it per user. With it, the same
-- answer is a sum over THIRTY rows.
--
-- Maintained incrementally as fills settle, not by a nightly batch:
--
--   INSERT INTO fee_user_daily_volumes (user_id, trade_date, product, volume, trade_count)
--   VALUES (?, CURRENT_DATE, ?, ?, 1)
--   ON DUPLICATE KEY UPDATE volume = volume + VALUES(volume),
--                           trade_count = trade_count + 1;
--
-- One primary-key upsert per fill, alongside the ledger entry already being written. The
-- daily job then only reads.
--
-- It still needs a periodic repair pass recomputing the last few days from spot_trades:
-- a missed event or a bug would otherwise drift silently, and a fee table that is quietly
-- wrong is worse than one that is obviously broken.

CREATE TABLE fee_user_daily_volumes (
    id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,

    user_id       BIGINT       NOT NULL,
    trade_date    DATE         NOT NULL,
    product       VARCHAR(16)  NOT NULL,

    -- Quote-asset notional, scaled by 1e8. Volume has to be measured in one asset to be
    -- comparable across markets, and the quote side is what every pair has in common.
    volume        BIGINT       NOT NULL DEFAULT 0,
    trade_count   BIGINT       NOT NULL DEFAULT 0,

    created_at    TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at    TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                               ON UPDATE CURRENT_TIMESTAMP(6),

    -- The upsert target. Without this the incremental write would insert instead of add.
    CONSTRAINT uq_fee_user_daily_volumes UNIQUE (user_id, trade_date, product),

    CONSTRAINT ck_fee_user_daily_volumes_product CHECK (
        product IN ('SPOT', 'USDM_FUTURES', 'COINM_FUTURES', 'MARGIN')
    ),
    CONSTRAINT ck_fee_user_daily_volumes_amounts CHECK (volume >= 0 AND trade_count >= 0)
) ENGINE = InnoDB;

-- The tier job: one user's trailing window, one index range scan.
CREATE INDEX idx_fee_user_daily_volumes_user_product_date
    ON fee_user_daily_volumes (user_id, product, trade_date);
-- The retention sweep -- rows older than the window are only kept for reporting.
CREATE INDEX idx_fee_user_daily_volumes_trade_date ON fee_user_daily_volumes (trade_date);
-- The daily tier job starts by asking which users traded in the window
-- (product, trade_date >= ?): ordered this way the index covers it, and the job never
-- touches the table.
CREATE INDEX idx_fee_user_daily_volumes_product_date_user
    ON fee_user_daily_volumes (product, trade_date, user_id);

-- Each user's current tier, per product. A projection: recomputed by a daily job from
-- fee_user_daily_volumes, and rebuildable from it at any time.
--
-- It exists so the order path is ONE indexed read. The window is a trailing 30 days that
-- slides every day -- not a calendar month -- so the job runs daily and each run sums a
-- different thirty rows:
--
--   run Aug 25   window Jul 27 .. Aug 25
--   run Aug 26   window Jul 28 .. Aug 26     drops Jul 27, adds Aug 26
--
-- Daily at 00:00 UTC is the convention every major exchange publishes, and it is worth
-- matching because users ask when their level changes.

CREATE TABLE fee_user_tiers (
    id                BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,

    user_id           BIGINT       NOT NULL,
    product           VARCHAR(16)  NOT NULL,

    tier_level        INT          NOT NULL DEFAULT 0,

    -- What the level was based on. Kept so support can answer "why am I still VIP 1"
    -- without rerunning the calculation.
    volume_30d        BIGINT       NOT NULL DEFAULT 0,
    window_start      DATE         NULL,
    window_end        DATE         NULL,
    computed_at       TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    created_at        TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at        TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                   ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT uq_fee_user_tiers UNIQUE (user_id, product),
    CONSTRAINT ck_fee_user_tiers_product CHECK (
        product IN ('SPOT', 'USDM_FUTURES', 'COINM_FUTURES', 'MARGIN')
    ),
    CONSTRAINT ck_fee_user_tiers_level CHECK (tier_level >= 0),
    CONSTRAINT ck_fee_user_tiers_volume CHECK (volume_30d >= 0)
) ENGINE = InnoDB;

-- Finding stale rows the daily job has not reached yet.
CREATE INDEX idx_fee_user_tiers_computed_at ON fee_user_tiers (computed_at);

-- A negotiated rate that beats whatever tier the user's volume earned. No row means they
-- pay the published schedule, which is what almost everyone does -- this table is for
-- market makers and institutional accounts.
--
-- Per product, so a maker rebate on futures does not silently apply to spot. In the fee
-- domain rather than user, because the concept spans spot, futures and margin.

CREATE TABLE fee_user_overrides (
    id              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,

    user_id         BIGINT       NOT NULL,
    product         VARCHAR(16)  NOT NULL,

    maker_fee_bps   INT          NOT NULL,
    taker_fee_bps   INT          NOT NULL,

    -- Why, and who approved it. Fee overrides are what an auditor asks about first.
    reason          VARCHAR(255) NULL,
    granted_by      BIGINT       NULL,

    -- Negotiated rates expire so they get renegotiated rather than forgotten.
    expires_at      TIMESTAMP(6) NULL,

    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                 ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT uq_fee_user_overrides UNIQUE (user_id, product),
    CONSTRAINT ck_fee_user_overrides_product CHECK (
        product IN ('SPOT', 'USDM_FUTURES', 'COINM_FUTURES', 'MARGIN')
    ),
    CONSTRAINT ck_fee_user_overrides_maker CHECK (maker_fee_bps > -10000 AND maker_fee_bps < 10000),
    CONSTRAINT ck_fee_user_overrides_taker CHECK (taker_fee_bps > -10000 AND taker_fee_bps < 10000)
) ENGINE = InnoDB;

CREATE INDEX idx_fee_user_overrides_expires_at ON fee_user_overrides (expires_at);
