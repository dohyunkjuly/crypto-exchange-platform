-- The wallet domain: what can be held, where it lives on-chain, who holds how much, and the
-- ledger every balance is a projection of.
--
-- White-label (docs/reference/white-label.md): nothing here touches a blockchain. The
-- deposit, withdrawal, address and allowlist tables exist so an integrator has rows to map
-- their own custody onto; no service in this repo reads or writes them.
--
-- Foreign keys stay on the human-rate tables where the integrity is worth the lock. They
-- are absent from wallet_ledger_entries, the highest-volume insert in the system: an FK
-- check takes a shared lock on the parent row for the whole transaction, and every movement
-- in an asset would then queue on that asset's row.

-- What can be held. An asset is NOT a network: USDT is one asset that exists on Ethereum, Tron and Solana
-- with different contracts, fees and confirmation counts -- wallet_asset_networks is where that lives.
-- Collapsing the two into one "currency" column is the mistake that makes adding a chain
-- a migration across every balance you hold.
--
-- A surrogate id rather than the symbol as primary key: symbols get rebranded, and a
-- BIGINT foreign key is a third the width of a VARCHAR one on tables that will hold
-- hundreds of millions of rows.

CREATE TABLE wallet_assets (
    id                  BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,

    symbol              VARCHAR(20)  NOT NULL,
    name                VARCHAR(64)  NOT NULL,
    asset_type          VARCHAR(16)  NOT NULL,

    -- Display precision, not storage precision. Balances are always scaled by 1e8
    -- (see libs/common Money); this is how many places the UI shows and how many are
    -- meaningful for this asset.
    display_decimals    INT          NOT NULL,

    -- Three separate switches on purpose. Chain upgrades mean halting deposits while
    -- trading continues; a delisting means halting trading while withdrawals stay open
    -- so people can get their funds out.
    trading_enabled     BOOLEAN      NOT NULL DEFAULT FALSE,
    deposit_enabled     BOOLEAN      NOT NULL DEFAULT FALSE,
    withdrawal_enabled  BOOLEAN      NOT NULL DEFAULT FALSE,

    created_at          TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                     ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT uq_wallet_assets_symbol UNIQUE (symbol),
    CONSTRAINT ck_wallet_assets_type CHECK (asset_type IN ('CRYPTO', 'FIAT')),
    CONSTRAINT ck_wallet_assets_decimals CHECK (display_decimals BETWEEN 0 AND 18)
) ENGINE = InnoDB;

-- The chains themselves. Separate from assets because the relationship is many-to-many -- USDT lives on several
-- chains, and Ethereum carries many assets. wallet_asset_networks is the join, and it is where the
-- per-chain money lives (fees, confirmations, contract addresses).

CREATE TABLE wallet_networks (
    id                     BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,

    code                   VARCHAR(32)  NOT NULL,
    name                   VARCHAR(64)  NOT NULL,

    -- EVM chain id: 1 Ethereum, 56 BSC, 137 Polygon. NULL for anything not EVM, which is
    -- why it is not the primary key however tempting that looks.
    evm_chain_id           BIGINT       NULL,

    -- e.g. https://etherscan.io/tx/{txHash} -- so support can link a deposit without
    -- hardcoding an explorer per chain in the frontend.
    explorer_tx_url        VARCHAR(255) NULL,
    explorer_address_url   VARCHAR(255) NULL,

    -- Testnets live in this table too. Nothing in the schema distinguishes them, and
    -- crediting a real balance from a testnet deposit is a way to lose money.
    is_testnet             BOOLEAN      NOT NULL DEFAULT FALSE,
    is_active              BOOLEAN      NOT NULL DEFAULT FALSE,

    created_at             TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at             TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                        ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT uq_wallet_networks_code UNIQUE (code)
) ENGINE = InnoDB;

-- One asset on one chain. This is the row a deposit address or a withdrawal actually
-- belongs to, and the most important table in the wallet domain:
--
--   USDT / ETHEREUM   ERC20   0xdAC17F958D...   12 confs
--   USDT / TRON       TRC20   TR7NHqjeKQxG...   20 confs
--   USDT / SOLANA     SPL     Es9vMFrzaCER...   32 confs
--
-- Same balance to the user, three different addresses, three different fees. Send TRC-20
-- USDT to an ERC-20 address and it is gone -- which is why the withdrawal path must
-- resolve a row here rather than reasoning about the asset alone.

CREATE TABLE wallet_asset_networks (
    id                       BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,

    asset_id                 BIGINT       NOT NULL,
    network_id               BIGINT       NOT NULL,

    -- NULL means the chain's native coin (ETH on Ethereum, TRX on Tron). Set for tokens.
    contract_address         VARCHAR(128) NULL,
    token_standard           VARCHAR(16)  NULL,

    -- What the CHAIN uses, which is not what we store. ETH is 18 decimals on-chain; our
    -- balances are scaled by 1e8. This is the number the deposit path converts with, and
    -- the reason anything below 1e-8 is dust we cannot credit.
    chain_decimals           INT          NOT NULL,

    -- All scaled by 1e8, like every other amount in the system.
    min_deposit              BIGINT       NOT NULL DEFAULT 0,
    min_withdrawal           BIGINT       NOT NULL DEFAULT 0,
    withdrawal_fee           BIGINT       NOT NULL DEFAULT 0,

    -- How deep a deposit must be buried before we credit it. Higher for chains with
    -- cheap reorgs, higher again for large amounts.
    required_confirmations   INT          NOT NULL,

    -- Checked before a withdrawal is submitted. A typo'd address is unrecoverable, so
    -- this is the last cheap place to catch one.
    address_regex            VARCHAR(255) NULL,
    -- XRP, XLM, EOS and others share one deposit address and route by memo. Omitting the
    -- memo on those chains loses the deposit.
    requires_memo            BOOLEAN      NOT NULL DEFAULT FALSE,

    deposit_enabled          BOOLEAN      NOT NULL DEFAULT FALSE,
    withdrawal_enabled       BOOLEAN      NOT NULL DEFAULT FALSE,

    created_at               TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at               TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                          ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT uq_wallet_asset_networks UNIQUE (asset_id, network_id),
    CONSTRAINT fk_wallet_asset_networks_asset FOREIGN KEY (asset_id) REFERENCES wallet_assets (id),
    CONSTRAINT fk_wallet_asset_networks_network FOREIGN KEY (network_id) REFERENCES wallet_networks (id),

    CONSTRAINT ck_wallet_asset_networks_standard CHECK (
        token_standard IS NULL OR token_standard IN ('ERC20', 'TRC20', 'BEP20', 'SPL', 'NATIVE')
    ),
    CONSTRAINT ck_wallet_asset_networks_decimals CHECK (chain_decimals BETWEEN 0 AND 18),
    CONSTRAINT ck_wallet_asset_networks_confirmations CHECK (required_confirmations >= 0),
    CONSTRAINT ck_wallet_asset_networks_amounts CHECK (
        min_deposit >= 0 AND min_withdrawal >= 0 AND withdrawal_fee >= 0
    )
) ENGINE = InnoDB;

-- "Which chains can I withdraw USDT on?" -- the deposit and withdrawal screens.
CREATE INDEX idx_wallet_asset_networks_asset_id ON wallet_asset_networks (asset_id);

-- One row per user per asset. This is the hot table: read before every order, written on
-- every fill.
--
-- available + locked, not one number. Placing an order does not spend anything -- it moves
-- funds from available to locked, so the money is still yours but cannot be spent twice.
-- Cancel and it moves back; fill and locked is debited. A single `balance` column makes
-- "can this user afford another order" unanswerable.
--
-- This is a PROJECTION. wallet_ledger_entries is the truth, and these two numbers can be
-- rebuilt by replaying it. Keeping both means the read path is one indexed row rather
-- than a sum over the user's entire history.

CREATE TABLE wallet_balances (
    id          BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,

    -- No foreign key to user_users, deliberately. Crossing a domain with an FK is exactly
    -- what makes moving wallet to its own database impossible later.
    user_id     BIGINT       NOT NULL,
    asset_id    BIGINT       NOT NULL,

    -- Scaled by 1e8, like every amount in the system.
    available   BIGINT       NOT NULL DEFAULT 0,
    locked      BIGINT       NOT NULL DEFAULT 0,

    -- JPA optimistic locking. Two concurrent fills against the same balance would
    -- otherwise be a lost update, and a lost update here is money invented or destroyed.
    version     BIGINT       NOT NULL DEFAULT 0,

    created_at  TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                             ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT uq_wallet_balances_user_asset UNIQUE (user_id, asset_id),
    CONSTRAINT fk_wallet_balances_asset FOREIGN KEY (asset_id) REFERENCES wallet_assets (id),

    -- The invariant worth having the database enforce: a negative balance is not a bug
    -- to be found later, it is a transaction that must fail now.
    CONSTRAINT ck_wallet_balances_non_negative CHECK (available >= 0 AND locked >= 0)
) ENGINE = InnoDB;

-- "Show me my portfolio" -- every balance for one user in one index scan.
CREATE INDEX idx_wallet_balances_user_id ON wallet_balances (user_id);

-- The truth every balance is a projection of. App-generated UUID ids, like spot_trades and
-- spot_order_fill_log: an IDENTITY id makes Hibernate read the generated value back after
-- each insert, which defeats JDBC batching on the one table that needs it most.
CREATE TABLE wallet_ledger_entries (
    id                BINARY(16)   NOT NULL PRIMARY KEY,

    user_id           BIGINT       NOT NULL,
    asset_id          BIGINT       NOT NULL,

    entry_type        VARCHAR(32)  NOT NULL,

    -- Signed. Positive credits the user, negative debits them, and the sum over a user
    -- and asset must equal their balance. Storing an unsigned amount plus a direction
    -- flag means every query has to remember to apply the flag.
    amount            BIGINT       NOT NULL,

    -- The balance immediately after this entry. Redundant with the running sum, and worth
    -- it: it turns "why is this balance wrong" from a full replay into a binary search.
    balance_after     BIGINT       NOT NULL,

    -- What caused it. reference_id is a string because it points at several kinds of
    -- thing -- order ids will be ULIDs, deposits are BIGINTs.
    reference_type    VARCHAR(32)  NULL,
    reference_id      VARCHAR(64)  NULL,

    -- The single most important column here. Wallet learns about fills by consuming Kafka,
    -- and Kafka delivers at least once -- so the same trade event WILL arrive twice, and
    -- the second time must not credit anything. A unique key per logical movement makes
    -- the duplicate insert fail instead of inventing money.
    idempotency_key   VARCHAR(128) NOT NULL,

    created_at        TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT uq_wallet_ledger_entries_idempotency UNIQUE (idempotency_key),

    -- No CHECK on entry_type, same reasoning as user_auth_events: the taxonomy grows with
    -- every feature and a CHECK would mean a migration each time. The Java enum guards
    -- the write path.
    CONSTRAINT ck_wallet_ledger_entries_amount CHECK (amount <> 0)
) ENGINE = InnoDB;

-- The account statement: one user, one asset, newest first.
CREATE INDEX idx_wallet_ledger_entries_user_asset_created
    ON wallet_ledger_entries (user_id, asset_id, created_at);
-- "What did this trade do to balances?" -- reconciliation and support.
CREATE INDEX idx_wallet_ledger_entries_reference
    ON wallet_ledger_entries (reference_type, reference_id);
-- No FK to wallet_assets (see the header), and no index by asset alone: nothing queries
-- ledger entries that way.

-- Where a user sends funds to deposit. One per user per asset_network, derived from a
-- master seed rather than stored as a private key -- the derivation path is enough to
-- re-derive it, and the seed never touches this database.
--
-- memo is NOT NULL DEFAULT '' rather than nullable, and that is deliberate. XRP, XLM and
-- EOS share one platform address across every user and route by memo, so uniqueness has
-- to be over (address, memo). MySQL permits duplicate rows when part of a unique key is
-- NULL, which would let the same address be issued twice on account-based chains -- the
-- empty string closes that hole.

CREATE TABLE wallet_deposit_addresses (
    id                  BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,

    -- No FK: the user lives in another domain.
    user_id             BIGINT       NOT NULL,
    asset_network_id    BIGINT       NOT NULL,

    address             VARCHAR(128) NOT NULL,
    memo                VARCHAR(64)  NOT NULL DEFAULT '',

    -- BIP-44, e.g. m/44'/60'/0'/0/12345. Recorded so the address can be re-derived from
    -- the seed during recovery, and so nothing has to trust this row to be correct.
    derivation_path     VARCHAR(64)  NULL,
    derivation_index    BIGINT       NULL,

    -- Addresses are rotated rather than deleted: funds sent to an old one still arrive
    -- and still have to be credited.
    is_active           BOOLEAN      NOT NULL DEFAULT TRUE,

    created_at          TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                     ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT uq_wallet_deposit_addresses UNIQUE (asset_network_id, address, memo),
    CONSTRAINT fk_wallet_deposit_addresses_asset_network
        FOREIGN KEY (asset_network_id) REFERENCES wallet_asset_networks (id)
) ENGINE = InnoDB;

-- "Show me my BTC deposit address" -- the deposit screen.
CREATE INDEX idx_wallet_deposit_addresses_user_asset_network
    ON wallet_deposit_addresses (user_id, asset_network_id);
-- The chain watcher resolves an incoming address to a user.
CREATE INDEX idx_wallet_deposit_addresses_address ON wallet_deposit_addresses (address);

-- An incoming on-chain transfer, from the moment it is seen in the mempool to the moment
-- the balance is credited.
--
-- The unique key over (asset_network_id, tx_hash, tx_output_index) is the whole safety
-- story. A chain watcher polls, restarts, replays, and sees the same transaction many
-- times; without that key each sighting is a fresh credit. tx_output_index exists because
-- Bitcoin and other UTXO chains can pay us twice in one transaction, so the hash alone is
-- not unique. It is 0 on account-based chains, and NOT NULL so the unique key holds --
-- MySQL permits duplicate rows when part of a unique key is NULL.

CREATE TABLE wallet_deposits (
    id                       BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,

    user_id                  BIGINT       NOT NULL,
    asset_id                 BIGINT       NOT NULL,
    asset_network_id         BIGINT       NOT NULL,
    deposit_address_id       BIGINT       NULL,

    tx_hash                  VARCHAR(128) NOT NULL,
    tx_output_index          INT          NOT NULL DEFAULT 0,
    from_address             VARCHAR(128) NULL,

    -- Scaled by 1e8. Anything the chain reports below 1e-8 is dust we cannot represent.
    amount                   BIGINT       NOT NULL,

    confirmations            INT          NOT NULL DEFAULT 0,
    -- Snapshotted from wallet_asset_networks when the deposit was first seen. If the
    -- requirement is raised later, a deposit already in flight keeps the rule it started
    -- under rather than silently needing more.
    required_confirmations   INT          NOT NULL,

    -- Kept so a reorg can be detected: the block that contained this transaction being
    -- replaced is exactly the case ORPHANED exists for.
    block_number             BIGINT       NULL,
    block_hash               VARCHAR(128) NULL,

    status                   VARCHAR(24)  NOT NULL DEFAULT 'DETECTED',

    -- The ledger entry that credited the balance. Null until CREDITED, and the link that
    -- lets reconciliation prove every credited deposit moved money exactly once.
    ledger_entry_id          BIGINT       NULL,

    detected_at              TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    credited_at              TIMESTAMP(6) NULL,

    created_at               TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at               TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                          ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT uq_wallet_deposits_tx UNIQUE (asset_network_id, tx_hash, tx_output_index),
    CONSTRAINT fk_wallet_deposits_asset FOREIGN KEY (asset_id) REFERENCES wallet_assets (id),
    CONSTRAINT fk_wallet_deposits_asset_network
        FOREIGN KEY (asset_network_id) REFERENCES wallet_asset_networks (id),
    CONSTRAINT fk_wallet_deposits_address
        FOREIGN KEY (deposit_address_id) REFERENCES wallet_deposit_addresses (id),

    CONSTRAINT ck_wallet_deposits_amount CHECK (amount > 0),
    CONSTRAINT ck_wallet_deposits_status CHECK (status IN (
        'DETECTED',     -- seen, not yet buried deep enough
        'CONFIRMING',   -- in a block, accumulating confirmations
        'CREDITED',     -- balance moved
        'ORPHANED',     -- the block was reorged away
        'REJECTED',     -- AML screening or a blocked source
        'FAILED'
    ))
) ENGINE = InnoDB;

-- The user's deposit history.
CREATE INDEX idx_wallet_deposits_user_created ON wallet_deposits (user_id, created_at);
-- The confirmation sweep: everything not yet buried deep enough.
CREATE INDEX idx_wallet_deposits_status ON wallet_deposits (status, detected_at);
-- Support looking up a transaction the user pasted in.
CREATE INDEX idx_wallet_deposits_tx_hash ON wallet_deposits (tx_hash);

-- An outgoing transfer. The most dangerous row in the schema: it is the only path by which
-- value leaves the platform, and every mistake on it is irreversible.
--
-- Three amounts rather than one. The user asks for `amount`; `fee` is what the chain and
-- the platform take; `net_amount` is what is actually broadcast. Storing only two of the
-- three means somebody eventually subtracts in the wrong direction.
--
-- idempotency_key is client-supplied and unique: a double-tapped withdraw button, or a
-- retried HTTP request, must not send twice.

CREATE TABLE wallet_withdrawals (
    id                       BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,

    user_id                  BIGINT       NOT NULL,
    asset_id                 BIGINT       NOT NULL,
    asset_network_id         BIGINT       NOT NULL,

    to_address               VARCHAR(128) NOT NULL,
    memo                     VARCHAR(64)  NOT NULL DEFAULT '',

    -- All scaled by 1e8. amount = net_amount + fee.
    amount                   BIGINT       NOT NULL,
    fee                      BIGINT       NOT NULL,
    net_amount               BIGINT       NOT NULL,

    status                   VARCHAR(24)  NOT NULL DEFAULT 'REQUESTED',

    tx_hash                  VARCHAR(128) NULL,
    block_number             BIGINT       NULL,
    confirmations            INT          NOT NULL DEFAULT 0,

    -- Above a threshold, or to an address seen for the first time, a human signs off.
    -- The two-person rule is the last defence when an account is already compromised.
    requires_manual_review   BOOLEAN      NOT NULL DEFAULT FALSE,
    approved_by              BIGINT       NULL,
    approved_at              TIMESTAMP(6) NULL,

    rejection_reason         VARCHAR(255) NULL,

    -- Client-supplied. A retried request reuses it and gets the original withdrawal back
    -- rather than creating a second one.
    idempotency_key          VARCHAR(128) NOT NULL,

    -- The debit. Null until the funds actually leave the balance.
    ledger_entry_id          BIGINT       NULL,

    requested_at             TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    completed_at             TIMESTAMP(6) NULL,

    created_at               TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at               TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                          ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT uq_wallet_withdrawals_idempotency UNIQUE (idempotency_key),
    CONSTRAINT fk_wallet_withdrawals_asset FOREIGN KEY (asset_id) REFERENCES wallet_assets (id),
    CONSTRAINT fk_wallet_withdrawals_asset_network
        FOREIGN KEY (asset_network_id) REFERENCES wallet_asset_networks (id),

    CONSTRAINT ck_wallet_withdrawals_amounts CHECK (
        amount > 0 AND fee >= 0 AND net_amount > 0 AND amount = net_amount + fee
    ),
    CONSTRAINT ck_wallet_withdrawals_status CHECK (status IN (
        'REQUESTED',         -- created, funds locked
        'PENDING_APPROVAL',  -- waiting on a human
        'APPROVED',
        'BROADCASTING',      -- handed to the signer
        'BROADCAST',         -- on the chain, not yet confirmed
        'CONFIRMED',         -- done
        'REJECTED',          -- refused by review or screening
        'CANCELLED',         -- withdrawn by the user before broadcast
        'FAILED'             -- broadcast failed; funds returned
    ))
) ENGINE = InnoDB;

-- The user's withdrawal history.
CREATE INDEX idx_wallet_withdrawals_user_created ON wallet_withdrawals (user_id, created_at);
-- The approval queue, and the confirmation sweep.
CREATE INDEX idx_wallet_withdrawals_status ON wallet_withdrawals (status, requested_at);
CREATE INDEX idx_wallet_withdrawals_tx_hash ON wallet_withdrawals (tx_hash);

-- Saved withdrawal destinations. Optionally the ONLY destinations a user may withdraw to,
-- which is the setting that turns a stolen session into an inconvenience rather than a
-- drained account.
--
-- activates_at is the point of the table. A newly added address is unusable for a cooling
-- period -- typically 24 hours -- so an attacker who gets in cannot add their own address
-- and withdraw in the same session. The delay gives the notification email time to reach
-- a human who did not add it.

CREATE TABLE wallet_address_allowlist (
    id                  BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,

    user_id             BIGINT       NOT NULL,
    asset_network_id    BIGINT       NOT NULL,

    address             VARCHAR(128) NOT NULL,
    memo                VARCHAR(64)  NOT NULL DEFAULT '',
    label               VARCHAR(64)  NULL,

    status              VARCHAR(16)  NOT NULL DEFAULT 'PENDING',

    -- Not usable before this. See above.
    activates_at        TIMESTAMP(6) NOT NULL,
    removed_at          TIMESTAMP(6) NULL,

    created_at          TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                     ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT uq_wallet_address_allowlist
        UNIQUE (user_id, asset_network_id, address, memo),
    CONSTRAINT fk_wallet_address_allowlist_asset_network
        FOREIGN KEY (asset_network_id) REFERENCES wallet_asset_networks (id),

    CONSTRAINT ck_wallet_address_allowlist_status CHECK (
        status IN ('PENDING', 'ACTIVE', 'REMOVED')
    )
) ENGINE = InnoDB;

-- The withdrawal check: is this destination allowed for this user, and is it live yet.
CREATE INDEX idx_wallet_address_allowlist_user
    ON wallet_address_allowlist (user_id, asset_network_id, status);
-- The sweep that promotes PENDING to ACTIVE once the cooling period has passed.
CREATE INDEX idx_wallet_address_allowlist_activates_at
    ON wallet_address_allowlist (status, activates_at);
