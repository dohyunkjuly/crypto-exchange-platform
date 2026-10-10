-- The spot domain: markets, orders, trades, and the caps on how many orders an account may
-- hold open.
--
-- No foreign keys on the fill path (spot_orders -> spot_symbols, spot_trades -> anything).
-- An FK check takes a shared lock on the parent row for the whole transaction, and one
-- large taker sweeping fifty makers is fifty inserts referencing the same taker order id,
-- while that row is being updated with its filled quantity. Two of those interleaved is a
-- deadlock on the one path that has to be fastest. The engine writes an order before any
-- fill can reference it, so a dangling reference is a serious bug, and the FK's answer to
-- it would be to lose a fill that happened. Reconciliation catches it instead. Foreign keys
-- stay where writes are rare and the integrity is worth the lock.

-- A tradable market. Base is what you buy and sell, quote is what you price and pay in.
-- Most of this table is the rule set every order is validated against before it reaches
-- the engine: without a tick size the book fragments into meaningless levels, and without
-- a minimum notional it fills with dust that costs more to match than it is worth.
--
-- base_asset_id and quote_asset_id name wallet assets without a foreign key: another domain.
-- Markets are seeded (db/seed), never shipped in the schema: an operator lists their own.
CREATE TABLE spot_symbols (
    id                          BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,

    symbol                      VARCHAR(20)  NOT NULL,
    base_asset_id               BIGINT       NOT NULL,
    quote_asset_id              BIGINT       NOT NULL,

    -- Which command partition this market's orders go to, chosen per market rather than
    -- hashed from the symbol: a hash cannot promise a busy market a partition, and so an
    -- engine thread, of its own. Fixed once the market has traded
    -- (docs/reference/command-partition.md).
    command_partition           INT          NOT NULL,

    status                      VARCHAR(16)  NOT NULL DEFAULT 'PRE_TRADING',

    -- Minimum price increment, scaled by 1e8; every order price is a multiple. Too fine
    -- and the book fragments across thousands of levels one satoshi apart; too coarse and
    -- the spread cannot narrow.
    price_tick                  BIGINT       NOT NULL,

    -- The order-book grouping selector ("0.01 / 0.1 / 1 / 10"). A comma-separated list of
    -- ascending 1e8-scaled longs, NOT a JSON array: JSON numbers pass through MySQL,
    -- Jackson and Hibernate on paths where an integer becomes a double, and money is
    -- BIGINT everywhere here for exactly that reason. As text, the only way to read one
    -- is Long.parseLong. NULL means "not configured", and a working selector is derived
    -- from price_tick, so a market nobody configured is not a broken screen.
    depth_groupings             VARCHAR(255) NULL
        COMMENT 'Ascending 1e8-scaled price groupings, comma separated. NULL derives from price_tick.',

    -- Minimum quantity increment, and the bounds around it.
    qty_step                    BIGINT       NOT NULL,
    min_qty                     BIGINT       NOT NULL,
    max_qty                     BIGINT       NOT NULL,

    -- Separate bounds for market orders, because a market order SWEEPS: a resting order at
    -- a bad price simply never fills, an oversized market order walks down through worse
    -- levels and fills at an average the user never intended. The market-order lot filter.
    -- NULL means "no separate rule, use the limit-order one". A market sell has no
    -- notional check (no price until it matches), so market_min_qty is also its dust floor.
    market_min_qty              BIGINT       NULL,
    market_max_qty              BIGINT       NULL,
    market_qty_step             BIGINT       NULL,

    -- price * quantity, in the quote asset. The floor that keeps dust off the book.
    min_notional                BIGINT       NOT NULL,
    max_notional                BIGINT       NULL,

    -- For a market buy sized in the quote asset ("spend 100 USDT"), where the base
    -- quantity is not knowable until it fills, so min_qty cannot constrain it.
    min_quote_qty               BIGINT       NULL,
    max_quote_qty               BIGINT       NULL,

    -- How far from the last traded price an order may sit, in basis points. The
    -- fat-finger guard.
    price_band_bps              INT          NULL,

    -- Per user, per market. Without a cap one account can fill the book with thousands of
    -- resting orders, which costs the engine memory and everyone else latency. Conditional
    -- orders get their own, smaller cap: a pile of stops parked at one price all fire
    -- together the instant it prints, in one synchronous burst.
    max_open_orders             INT          NOT NULL DEFAULT 200,
    max_open_conditional_orders INT          NOT NULL DEFAULT 50,

    market_orders_enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    stop_orders_enabled         BOOLEAN      NOT NULL DEFAULT FALSE,

    created_at                  TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at                  TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                             ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT uq_spot_symbols_symbol UNIQUE (symbol),
    CONSTRAINT uq_spot_symbols_pair UNIQUE (base_asset_id, quote_asset_id),

    CONSTRAINT ck_spot_symbols_status CHECK (status IN (
        'PRE_TRADING',   -- listed, not yet open
        'TRADING',
        'HALTED',        -- temporarily stopped; open orders stand
        'CANCEL_ONLY',   -- no new orders, cancels allowed: how a market is wound down
        'POST_TRADING',
        'DELISTED'
    )),
    CONSTRAINT ck_spot_symbols_command_partition CHECK (command_partition >= 0),
    CONSTRAINT ck_spot_symbols_different_assets CHECK (base_asset_id <> quote_asset_id),
    CONSTRAINT ck_spot_symbols_increments CHECK (price_tick > 0 AND qty_step > 0),
    -- Structure only: that every grouping is a multiple of price_tick is enforced in Java,
    -- since a regex cannot reach another column. This still rejects what would otherwise
    -- reach Long.parseLong at request time.
    CONSTRAINT ck_spot_symbols_depth_groupings CHECK (
        depth_groupings IS NULL OR depth_groupings REGEXP '^[1-9][0-9]*(,[1-9][0-9]*)*$'
    ),
    CONSTRAINT ck_spot_symbols_qty CHECK (min_qty > 0 AND max_qty >= min_qty),
    CONSTRAINT ck_spot_symbols_market_qty CHECK (
        (market_min_qty IS NULL OR market_min_qty > 0)
        AND (market_max_qty IS NULL OR market_min_qty IS NULL OR market_max_qty >= market_min_qty)
        AND (market_qty_step IS NULL OR market_qty_step > 0)
    ),
    CONSTRAINT ck_spot_symbols_notional CHECK (
        min_notional >= 0 AND (max_notional IS NULL OR max_notional >= min_notional)
    ),
    CONSTRAINT ck_spot_symbols_quote_qty CHECK (
        (min_quote_qty IS NULL OR min_quote_qty > 0)
        AND (max_quote_qty IS NULL OR min_quote_qty IS NULL OR max_quote_qty >= min_quote_qty)
    ),
    CONSTRAINT ck_spot_symbols_max_open_orders CHECK (max_open_orders > 0),
    CONSTRAINT ck_spot_symbols_max_open_conditional_orders CHECK (max_open_conditional_orders > 0)
) ENGINE = InnoDB;

-- "What can I trade?": the markets list, and the engine's startup load.
CREATE INDEX idx_spot_symbols_status ON spot_symbols (status);

-- An order, from acceptance to its terminal state. Written by whichever app accepted it,
-- then updated by settlement as the engine reports what happened (docs/reference/locked-funds.md).
--
-- The id is a ULID assigned by the API, not AUTO_INCREMENT: the API returns it in the 202
-- before anything is written, and the engine references it while matching in memory.
-- Time-ordered, so inserts stay sequential and the primary key does not fragment.
-- BINARY(16) because Hibernate maps a Java UUID onto it.
--
-- maker_fee_bps and taker_fee_bps are STAMPED at acceptance. Fills charge the stamped rate
-- and never read the fee tables: one resolution per order, a rate that cannot change
-- halfway through a partial fill, and a charge explainable years later.
CREATE TABLE spot_orders (
    id                          BINARY(16)    NOT NULL PRIMARY KEY,

    -- Client-supplied, so a retried submit returns the original order instead of placing a
    -- second one. Unique per user, not globally.
    client_order_id             VARCHAR(64)   NULL,

    -- No foreign key: the user lives in another domain.
    user_id                     BIGINT        NOT NULL,
    symbol_id                   BIGINT        NOT NULL,

    side                        VARCHAR(4)    NOT NULL,
    order_type                  VARCHAR(20)   NOT NULL,
    time_in_force               VARCHAR(8)    NOT NULL,

    -- All scaled by 1e8. price is NULL on a market-firing order, which has no limit.
    price                       BIGINT        NULL,
    -- A trailing order's stop_price is an optional ACTIVATION price rather than a fixed
    -- trigger; absent, tracking starts at once from the market price.
    stop_price                  BIGINT        NULL,
    -- Same bounds the engine enforces, 0.1% to 20%. The engine is what matters; this keeps
    -- a malformed row from ever landing.
    trailing_delta_bps          INT           NULL,

    -- Stays ZERO for the life of a quote_order_qty order: the base amount is not knowable
    -- until it fills.
    quantity                    BIGINT        NOT NULL,
    -- "Buy $100 of BTC": a market-firing buy sized in the quote asset. Only a MARKET, or a
    -- plain STOP_LOSS/TAKE_PROFIT that becomes one, can carry it.
    quote_order_qty             BIGINT        NULL,

    filled_quantity             BIGINT        NOT NULL DEFAULT 0,
    -- Sum of price*qty over the fills, so average fill price is a division rather than an
    -- aggregate over spot_trades.
    cumulative_quote_quantity   BIGINT        NOT NULL DEFAULT 0,

    -- PENDING_TRIGGER is a conditional order parked in the engine's trigger book: not NEW
    -- (resting, matchable, in the depth), not terminal. Once it fires the same row becomes
    -- NEW and follows the normal lifecycle.
    status                      VARCHAR(16)   NOT NULL DEFAULT 'NEW',
    reject_reason               VARCHAR(64)   NULL,

    -- Signed basis points, resolved once at acceptance. See above.
    maker_fee_bps               INT           NOT NULL,
    taker_fee_bps               INT           NOT NULL,

    -- GTD only.
    expires_at                  TIMESTAMP(6)  NULL,

    -- Two concurrent fills against this row would otherwise be a lost update, and a lost
    -- update here is a silently wrong filled_quantity.
    version                     BIGINT        NOT NULL DEFAULT 0,

    -- One amend in flight per order. The API claims it with a conditional UPDATE and
    -- refuses a second amend while it is set; settlement clears it on the engine's
    -- outcome. Two amends computed from the same stale row could leave the order
    -- under-locked (docs/reference/locked-funds.md).
    amend_pending               BOOLEAN       NOT NULL DEFAULT FALSE,

    created_at                  TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at                  TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                              ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT uq_spot_orders_client_order_id UNIQUE (user_id, client_order_id),

    CONSTRAINT ck_spot_orders_side CHECK (side IN ('BUY', 'SELL')),
    CONSTRAINT ck_spot_orders_type CHECK (order_type IN (
        'LIMIT', 'MARKET',
        'STOP_LOSS', 'STOP_LOSS_LIMIT',
        'TAKE_PROFIT', 'TAKE_PROFIT_LIMIT',
        'LIMIT_MAKER'    -- rejected outright if it would take, so it can only ever be a maker
    )),
    CONSTRAINT ck_spot_orders_tif CHECK (time_in_force IN ('GTC', 'IOC', 'FOK', 'GTD')),
    CONSTRAINT ck_spot_orders_status CHECK (status IN (
        'PENDING_TRIGGER',
        'NEW', 'PARTIALLY_FILLED', 'FILLED',
        'CANCELED', 'PENDING_CANCEL', 'REJECTED', 'EXPIRED'
    )),
    CONSTRAINT ck_spot_orders_quantity CHECK (quantity > 0 OR quote_order_qty IS NOT NULL),
    CONSTRAINT ck_spot_orders_price CHECK (price IS NULL OR price > 0),
    -- The invariant the engine must never break: a fill beyond what was ordered is money
    -- created from nothing. For a quote-sized order the bound is the quote budget instead.
    CONSTRAINT ck_spot_orders_filled CHECK (
        filled_quantity >= 0
        AND (quote_order_qty IS NOT NULL OR filled_quantity <= quantity)
    ),
    CONSTRAINT ck_spot_orders_cumulative_quote_quantity CHECK (
        quote_order_qty IS NULL OR cumulative_quote_quantity <= quote_order_qty
    ),
    -- A limit order without a price is not an order.
    CONSTRAINT ck_spot_orders_limit_has_price CHECK (
        order_type NOT IN ('LIMIT', 'STOP_LOSS_LIMIT', 'TAKE_PROFIT_LIMIT', 'LIMIT_MAKER')
        OR price IS NOT NULL
    ),
    CONSTRAINT ck_spot_orders_stop_has_trigger CHECK (
        order_type NOT IN ('STOP_LOSS', 'STOP_LOSS_LIMIT', 'TAKE_PROFIT', 'TAKE_PROFIT_LIMIT')
        OR stop_price IS NOT NULL
        OR trailing_delta_bps IS NOT NULL
    ),
    CONSTRAINT ck_spot_orders_trailing_delta_bounds CHECK (
        trailing_delta_bps IS NULL OR trailing_delta_bps BETWEEN 10 AND 2000
    ),
    CONSTRAINT ck_spot_orders_quote_qty_scope CHECK (
        quote_order_qty IS NULL OR order_type IN ('MARKET', 'STOP_LOSS', 'TAKE_PROFIT')
    )
) ENGINE = InnoDB;

-- "My orders, newest first."
CREATE INDEX idx_spot_orders_user_created ON spot_orders (user_id, created_at);
-- "My open orders": the query the trading screen polls.
CREATE INDEX idx_spot_orders_user_status ON spot_orders (user_id, status);
-- Every live order for one market.
CREATE INDEX idx_spot_orders_symbol_status ON spot_orders (symbol_id, status);
-- The GTD expiry sweep.
CREATE INDEX idx_spot_orders_expires_at ON spot_orders (expires_at);
-- Order history filtered and sorted by last change.
CREATE INDEX idx_spot_orders_user_updated ON spot_orders (user_id, updated_at);

-- An execution: two orders crossed. The public tape, and the input to every candle, every
-- balance movement and every volume rollup.
--
-- One row per trade carrying both sides, rather than one row per participant: the tape is
-- one-sided per execution and reconciliation is simpler when a trade is one fact. The cost
-- is that "my trades" reaches the row through either user column, hence the two indexes.
-- If that ever becomes the bottleneck the answer is a derived per-user table, not a copy.
CREATE TABLE spot_trades (
    id                  BINARY(16)    NOT NULL PRIMARY KEY,

    symbol_id           BIGINT        NOT NULL,

    -- All scaled by 1e8. quote_quantity is price*quantity carried rather than derived,
    -- because it is summed constantly.
    price               BIGINT        NOT NULL,
    quantity            BIGINT        NOT NULL,
    quote_quantity      BIGINT        NOT NULL,

    buyer_order_id      BINARY(16)    NOT NULL,
    seller_order_id     BINARY(16)    NOT NULL,
    buyer_user_id       BIGINT        NOT NULL,
    seller_user_id      BIGINT        NOT NULL,

    -- Which side rested. The maker gets the maker rate, which may be a rebate.
    buyer_is_maker      BOOLEAN       NOT NULL,

    -- Charged amounts, not rates, copied from the orders' stamped rates. The fee asset
    -- differs by side: a buyer pays in the base asset received, a seller in the quote.
    -- Signed: a maker rebate is stored as the negative it is, so a reader of this row
    -- alone can tell a rebate from a charge.
    buyer_fee           BIGINT        NOT NULL DEFAULT 0,
    buyer_fee_asset_id  BIGINT        NULL,
    seller_fee          BIGINT        NOT NULL DEFAULT 0,
    seller_fee_asset_id BIGINT        NULL,

    -- The engine's monotonic counter for this market. Two trades can share a microsecond;
    -- this is what makes "which came first" answerable, and what a replay reproduces.
    sequence            BIGINT        NOT NULL,
    executed_at         TIMESTAMP(6)  NOT NULL,

    created_at          TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT uq_spot_trades_symbol_sequence UNIQUE (symbol_id, sequence),
    CONSTRAINT ck_spot_trades_amounts CHECK (price > 0 AND quantity > 0 AND quote_quantity > 0),
    -- A negative fee is legitimate only for the side that rested: takers pay, makers may
    -- be paid. A negative taker fee is a sign error and must not reach the row.
    CONSTRAINT ck_spot_trades_fees CHECK (
        (buyer_fee >= 0 OR buyer_is_maker = TRUE)
        AND (seller_fee >= 0 OR buyer_is_maker = FALSE)
    ),
    -- Self-trade prevention lives in the engine and no mode allows one; this is so a bug
    -- cannot persist one.
    CONSTRAINT ck_spot_trades_no_self_trade CHECK (buyer_user_id <> seller_user_id)
) ENGINE = InnoDB;

-- The tape, and the source for every candle interval.
CREATE INDEX idx_spot_trades_symbol_executed ON spot_trades (symbol_id, executed_at);
-- "My fills", from each side.
CREATE INDEX idx_spot_trades_buyer ON spot_trades (buyer_user_id, executed_at);
CREATE INDEX idx_spot_trades_seller ON spot_trades (seller_user_id, executed_at);
-- Every fill of one order, for the order-detail view.
CREATE INDEX idx_spot_trades_buyer_order ON spot_trades (buyer_order_id);
CREATE INDEX idx_spot_trades_seller_order ON spot_trades (seller_order_id);

-- Which trades an order has already counted. Settlement sums one order's fills across a
-- batch into a single write; after a crash this is what answers "which of these trades has
-- this specific order already applied", a different question from spot_trades' own "has
-- this trade happened", since one trade's two sides land in two separate batched writes.
CREATE TABLE spot_order_fill_log (
    order_id    BINARY(16)    NOT NULL,
    trade_id    BINARY(16)    NOT NULL,
    created_at  TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    PRIMARY KEY (order_id, trade_id),
    -- Same domain, and human-rate relative to the fill itself, so the FK stays.
    CONSTRAINT fk_spot_order_fill_log_order FOREIGN KEY (order_id) REFERENCES spot_orders (id)
) ENGINE = InnoDB;

-- Caps one account across the WHOLE exchange, where spot_symbols.max_open_orders caps it on
-- one market: otherwise trading 40 symbols at 200+50 each is 10,000 open orders with nothing
-- catching it. The account-wide caps on open orders and open algo orders. There is
-- exactly one exchange, so exactly one row, and the row is part of the schema.
CREATE TABLE spot_exchange_order_limits (
    id                    TINYINT UNSIGNED NOT NULL PRIMARY KEY DEFAULT 1,

    max_num_orders        INT NOT NULL DEFAULT 1000,
    -- A subset of the above.
    max_num_algo_orders   INT NOT NULL DEFAULT 200,

    created_at            TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at            TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                       ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT ck_spot_exchange_order_limits_singleton CHECK (id = 1),
    CONSTRAINT ck_spot_exchange_order_limits_max_num_orders CHECK (max_num_orders > 0),
    CONSTRAINT ck_spot_exchange_order_limits_max_num_algo_orders
        CHECK (max_num_algo_orders > 0 AND max_num_algo_orders <= max_num_orders)
) ENGINE = InnoDB;

INSERT INTO spot_exchange_order_limits (id) VALUES (1);

-- Per-user overrides of the exchange-wide caps, the way fee_user_overrides sits beside
-- fee_tiers: no row means the default, which is what almost everyone is. Either column may
-- be overridden alone, so both are nullable: NULL means "inherit", not zero.
CREATE TABLE spot_user_order_limit_overrides (
    id                    BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id               BIGINT       NOT NULL,

    max_num_orders        INT          NULL,
    max_num_algo_orders   INT          NULL,

    -- Why, and who approved it: what an auditor asks about first.
    reason                VARCHAR(255) NULL,
    granted_by            BIGINT       NULL,
    -- Negotiated caps expire so they get renegotiated rather than forgotten.
    expires_at            TIMESTAMP(6) NULL,

    created_at            TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at            TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                       ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT uq_spot_user_order_limit_overrides UNIQUE (user_id),
    CONSTRAINT ck_spot_user_order_limit_overrides_not_empty CHECK (
        max_num_orders IS NOT NULL OR max_num_algo_orders IS NOT NULL
    ),
    CONSTRAINT ck_spot_user_order_limit_overrides_max_num_orders
        CHECK (max_num_orders IS NULL OR max_num_orders > 0),
    CONSTRAINT ck_spot_user_order_limit_overrides_max_num_algo_orders CHECK (
        (max_num_algo_orders IS NULL OR max_num_algo_orders > 0)
        AND (max_num_orders IS NULL OR max_num_algo_orders IS NULL
             OR max_num_algo_orders <= max_num_orders)
    )
) ENGINE = InnoDB;

CREATE INDEX idx_spot_user_order_limit_overrides_expires_at
    ON spot_user_order_limit_overrides (expires_at);

-- The per-market counterpart: a market maker's edge is usually one or two symbols, so the
-- override has to be nameable per symbol.
CREATE TABLE spot_symbol_user_order_limit_overrides (
    id                          BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id                     BIGINT       NOT NULL,
    symbol_id                   BIGINT       NOT NULL,

    max_open_orders             INT          NULL,
    max_open_conditional_orders INT          NULL,

    reason                      VARCHAR(255) NULL,
    granted_by                  BIGINT       NULL,
    expires_at                  TIMESTAMP(6) NULL,

    created_at                  TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at                  TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                             ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT fk_spot_symbol_user_order_limit_overrides_symbol
        FOREIGN KEY (symbol_id) REFERENCES spot_symbols (id),
    CONSTRAINT uq_spot_symbol_user_order_limit_overrides UNIQUE (user_id, symbol_id),
    CONSTRAINT ck_spot_symbol_user_order_limit_overrides_not_empty CHECK (
        max_open_orders IS NOT NULL OR max_open_conditional_orders IS NOT NULL
    ),
    CONSTRAINT ck_spot_symbol_user_order_limit_overrides_max_open_orders
        CHECK (max_open_orders IS NULL OR max_open_orders > 0),
    CONSTRAINT ck_spot_symbol_user_order_limit_overrides_max_open_cond_orders CHECK (
        (max_open_conditional_orders IS NULL OR max_open_conditional_orders > 0)
        AND (max_open_orders IS NULL OR max_open_conditional_orders IS NULL
             OR max_open_conditional_orders <= max_open_orders)
    )
) ENGINE = InnoDB;

CREATE INDEX idx_spot_symbol_user_order_limit_overrides_expires_at
    ON spot_symbol_user_order_limit_overrides (expires_at);
