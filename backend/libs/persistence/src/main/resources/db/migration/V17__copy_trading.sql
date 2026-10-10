-- Spot copy trading (docs/reference/copy-trading.md). A leader trades one account; a follower
-- funds a copy account tied to that leader, and each lead order that closes with something
-- filled is repeated in it. Both accounts are sub-accounts, so the wallet, the gate and
-- settlement need nothing new: what a sub is FOR is the only thing added to user_users.
--
-- No foreign keys: every id here that points at a user, an order, a market or an asset
-- points into another domain (docs/reference/migrations.md).

-- STANDARD is what every existing sub is, and what a PRIMARY or SYSTEM row always is.
ALTER TABLE user_users
    ADD COLUMN sub_purpose VARCHAR(16) NOT NULL DEFAULT 'STANDARD' AFTER type,
    ADD CONSTRAINT ck_user_users_sub_purpose CHECK (sub_purpose IN ('STANDARD', 'LEAD', 'COPY')),
    ADD CONSTRAINT ck_user_users_sub_purpose_is_a_subs CHECK (sub_purpose = 'STANDARD' OR type = 'SUB');

-- What staff set, for the whole exchange. One exchange, so one row, and the row is part of
-- the schema, as spot_exchange_order_limits is. Amounts are USDT, scaled by 1e8.
CREATE TABLE copy_settings (
    id                        TINYINT UNSIGNED NOT NULL PRIMARY KEY DEFAULT 1,

    -- The leader's part of a follower's realized profit.
    profit_share_bps          INT    NOT NULL DEFAULT 1000,
    -- How far above the leader's price a copied buy may fill; copy_market_caps overrides it.
    slippage_cap_bps          INT    NOT NULL DEFAULT 50,
    -- Failed copies in a row that close a copy account.
    failure_limit             INT    NOT NULL DEFAULT 5,

    max_followers_per_leader  INT    NOT NULL DEFAULT 300,
    max_copies_per_user       INT    NOT NULL DEFAULT 10,

    min_fixed_ratio_amount    BIGINT NOT NULL DEFAULT 10000000000,
    min_fixed_amount          BIGINT NOT NULL DEFAULT 1000000000,
    -- What a lead account must keep; only what is above it can be taken out.
    lead_min_balance          BIGINT NOT NULL DEFAULT 50000000000,
    -- On closing, a holding worth less than this is returned as it is rather than sold.
    close_sell_threshold      BIGINT NOT NULL DEFAULT 1000000000,

    created_at                TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at                TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                           ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT ck_copy_settings_singleton CHECK (id = 1),
    CONSTRAINT ck_copy_settings_profit_share_bps CHECK (profit_share_bps BETWEEN 0 AND 10000),
    CONSTRAINT ck_copy_settings_slippage_cap_bps CHECK (slippage_cap_bps BETWEEN 0 AND 10000),
    CONSTRAINT ck_copy_settings_counts CHECK (
        failure_limit > 0 AND max_followers_per_leader > 0 AND max_copies_per_user > 0),
    CONSTRAINT ck_copy_settings_amounts CHECK (
        min_fixed_ratio_amount > 0 AND min_fixed_amount > 0
        AND lead_min_balance >= 0 AND close_sell_threshold >= 0)
) ENGINE = InnoDB;

INSERT INTO copy_settings (id) VALUES (1);

-- A market's own slippage cap, where it differs: the deepest books can be held tighter.
CREATE TABLE copy_market_caps (
    symbol_id         BIGINT NOT NULL PRIMARY KEY,
    slippage_cap_bps  INT    NOT NULL,

    created_at        TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at        TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                   ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT ck_copy_market_caps_slippage_cap_bps CHECK (slippage_cap_bps BETWEEN 0 AND 10000)
) ENGINE = InnoDB;

-- An application to lead, and what became of it. The id is a ULID because it is the public
-- one: a profile's URL carries it, and neither the customer's id nor a counter may.
CREATE TABLE copy_leaders (
    id             BINARY(16)   NOT NULL PRIMARY KEY,

    -- The customer (a master), one application each.
    user_id        BIGINT       NOT NULL,
    -- The LEAD sub-account, made when staff approve; NULL until then.
    lead_user_id   BIGINT       NULL,

    -- What followers see instead of an email address.
    display_name   VARCHAR(40)  NOT NULL,
    bio            VARCHAR(500) NULL,

    status         VARCHAR(16)  NOT NULL,
    -- The admin who approved, rejected or suspended, and why.
    reviewed_by    BIGINT       NULL,
    reviewed_at    TIMESTAMP(6) NULL,
    review_reason  VARCHAR(255) NULL,

    created_at     TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at     TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT uq_copy_leaders_user_id UNIQUE (user_id),
    CONSTRAINT uq_copy_leaders_lead_user_id UNIQUE (lead_user_id),
    CONSTRAINT uq_copy_leaders_display_name UNIQUE (display_name),
    CONSTRAINT ck_copy_leaders_status CHECK (
        status IN ('APPLIED', 'ACTIVE', 'SUSPENDED', 'REJECTED', 'CLOSED')),
    INDEX idx_copy_leaders_status (status, created_at)
) ENGINE = InnoDB;

-- One follower copying one leader: the copy account and how it is going.
CREATE TABLE copy_follows (
    id                BINARY(16)  NOT NULL PRIMARY KEY,

    leader_id         BINARY(16)  NOT NULL,
    -- The follower (a master) and their COPY sub-account for this leader.
    user_id           BIGINT      NOT NULL,
    copy_user_id      BIGINT      NOT NULL,

    mode              VARCHAR(16) NOT NULL,
    -- FIXED_AMOUNT only: what each copied buy costs, USDT scaled by 1e8.
    amount_per_order  BIGINT      NULL,

    status            VARCHAR(16) NOT NULL,
    close_reason      VARCHAR(24) NULL,
    closed_at         TIMESTAMP(6) NULL,

    failures_in_row   INT         NOT NULL DEFAULT 0,
    -- Net of fees, signed: a copy account can be down. shared_profit is what the leader has
    -- been paid of it, and only ever grows.
    realized_profit   BIGINT      NOT NULL DEFAULT 0,
    shared_profit     BIGINT      NOT NULL DEFAULT 0,

    -- The copier and the follower both write this row: a copy landing as the follower stops.
    version           BIGINT      NOT NULL DEFAULT 0,
    created_at        TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at        TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                   ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT uq_copy_follows_copy_user_id UNIQUE (copy_user_id),
    CONSTRAINT ck_copy_follows_mode CHECK (mode IN ('FIXED_RATIO', 'FIXED_AMOUNT')),
    CONSTRAINT ck_copy_follows_amount_per_order CHECK (
        (mode = 'FIXED_AMOUNT') = (amount_per_order IS NOT NULL)
        AND (amount_per_order IS NULL OR amount_per_order > 0)),
    CONSTRAINT ck_copy_follows_status CHECK (status IN ('ACTIVE', 'CLOSING', 'CLOSED')),
    CONSTRAINT ck_copy_follows_close_reason CHECK (
        close_reason IS NULL
        OR close_reason IN ('STOPPED', 'FAILURES', 'LEADER_CLOSED', 'LEADER_SUSPENDED')),
    CONSTRAINT ck_copy_follows_counts CHECK (failures_in_row >= 0 AND shared_profit >= 0),
    -- Every active copy of one leader, on each of that leader's orders.
    INDEX idx_copy_follows_leader (leader_id, status),
    INDEX idx_copy_follows_user (user_id, status)
) ENGINE = InnoDB;

-- A lead account's order and the fraction it used: of the available USDT for a buy, of the
-- holding for a sell. Written in the placing transaction, because by the time the order
-- closes the balance has already moved and the fraction cannot be worked out.
CREATE TABLE copy_lead_orders (
    order_id      BINARY(16)  NOT NULL PRIMARY KEY,

    leader_id     BINARY(16)  NOT NULL,
    symbol_id     BIGINT      NOT NULL,
    side          VARCHAR(4)  NOT NULL,
    -- Scaled by 1e8: 100000000 is all of it.
    fraction      BIGINT      NOT NULL,

    -- When the copier handled its closing; NULL while the order is still open.
    copied_at     TIMESTAMP(6) NULL,
    created_at    TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT ck_copy_lead_orders_side CHECK (side IN ('BUY', 'SELL')),
    CONSTRAINT ck_copy_lead_orders_fraction CHECK (fraction > 0 AND fraction <= 100000000),
    INDEX idx_copy_lead_orders_leader (leader_id, created_at)
) ENGINE = InnoDB;

-- One order placed, or not placed, in a copy account. The unique key is the idempotency: an
-- event delivered twice finds the row and copies nothing again.
CREATE TABLE copy_orders (
    id             BINARY(16)  NOT NULL PRIMARY KEY,

    follow_id      BINARY(16)  NOT NULL,
    -- The lead order this copies; NULL for a sale made to close the account.
    lead_order_id  BINARY(16)  NULL,
    -- The order in the copy account; NULL when nothing could be placed.
    order_id       BINARY(16)  NULL,

    symbol_id      BIGINT      NOT NULL,
    side           VARCHAR(4)  NOT NULL,
    status         VARCHAR(16) NOT NULL,
    -- Why it failed or was skipped, for the follower's page and the success rate.
    reason         VARCHAR(32) NULL,

    created_at     TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at     TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT uq_copy_orders_lead_order_follow UNIQUE (lead_order_id, follow_id),
    CONSTRAINT uq_copy_orders_order_id UNIQUE (order_id),
    CONSTRAINT ck_copy_orders_side CHECK (side IN ('BUY', 'SELL')),
    CONSTRAINT ck_copy_orders_status CHECK (status IN ('PLACED', 'FILLED', 'FAILED', 'SKIPPED')),
    INDEX idx_copy_orders_follow (follow_id, created_at)
) ENGINE = InnoDB;

-- What a copy account holds of one asset and what it paid for it, so a sale knows its
-- profit without anything being valued at a market price.
CREATE TABLE copy_positions (
    id          BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,

    follow_id   BINARY(16)  NOT NULL,
    asset_id    BIGINT      NOT NULL,
    quantity    BIGINT      NOT NULL DEFAULT 0,
    -- What the quantity held cost in USDT, fees included, scaled by 1e8.
    cost        BIGINT      NOT NULL DEFAULT 0,

    version     BIGINT      NOT NULL DEFAULT 0,
    created_at  TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                             ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT uq_copy_positions_follow_asset UNIQUE (follow_id, asset_id),
    CONSTRAINT ck_copy_positions_amounts CHECK (quantity >= 0 AND cost >= 0)
) ENGINE = InnoDB;
