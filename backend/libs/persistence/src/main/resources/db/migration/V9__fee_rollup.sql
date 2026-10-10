-- What trading earned, summarised by the hour (docs/reference/treasury.md).
--
-- The fee account's balance is derived from customer-side ledger rows: every FEE debited from
-- a trader, less every REBATE credited to one. Summing those from the beginning of time on
-- every sweep is the scan this table exists to bound -- a closed hour's total can never
-- change, so it is computed once and added to thereafter.
--
-- Not a cache: nothing invalidates it and nothing keeps it in sync. It is a recorded fact
-- about an hour that is over, and it is checkable -- recompute the hour from the ledger and
-- compare. A wrong row can be repaired and the next sweep corrects itself, which is why this
-- is a total per hour rather than a "swept up to here" pointer: a wrong pointer moves money
-- and cannot be recovered from.

CREATE TABLE wallet_fee_hourly_totals (
    -- The hour this covers, UTC, inclusive: [period_start, period_start + 1 hour).
    period_start  TIMESTAMP(6) NOT NULL,
    asset_id      BIGINT       NOT NULL,

    -- FEE or REBATE, as written on the trader's own row. Never a house entry type: this
    -- table summarises what customers paid, not what has been moved because of it.
    entry_type    VARCHAR(32)  NOT NULL,

    -- Signed, scaled by 1e8, summed straight from wallet_ledger_entries.amount: a FEE is
    -- negative (it debits the trader), a REBATE positive. Earned is therefore -(FEE + REBATE),
    -- and doing the negation in one place rather than per caller is the whole point.
    amount        BIGINT       NOT NULL,

    created_at    TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    -- The natural key, and what makes a re-run or a backfill unable to duplicate an hour.
    PRIMARY KEY (period_start, asset_id, entry_type)
) ENGINE = InnoDB;

-- Both halves of "earned" read this way: a type and an asset over a time range. V2__wallet.sql
-- deliberately indexed the ledger by user, since nothing summed it by type until now.
CREATE INDEX idx_wallet_ledger_entries_type_asset_created
    ON wallet_ledger_entries (entry_type, asset_id, created_at);
