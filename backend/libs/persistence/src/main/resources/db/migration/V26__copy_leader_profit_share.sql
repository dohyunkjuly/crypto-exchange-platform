-- A leader's own profit share, set by staff; null means the exchange's (copy_settings).
-- Read once, when someone follows, and frozen on the follow (V21): changing it never touches
-- a follow that already exists (docs/reference/copy-trading.md).
ALTER TABLE copy_leaders ADD COLUMN profit_share_bps INT NULL;
ALTER TABLE copy_leaders ADD CONSTRAINT ck_copy_leaders_profit_share_bps
    CHECK (profit_share_bps IS NULL OR profit_share_bps BETWEEN 0 AND 10000);
