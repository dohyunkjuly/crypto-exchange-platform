-- How far above the leader's price a copied buy may fill is the follower's choice, made when
-- they follow (docs/reference/copy-trading.md). The exchange-wide cap and the per-market caps
-- staff set go: a follower knows how much they will pay to stay in step, and staff do not.
-- Existing follows keep what they had, the old default of 0.5%.

ALTER TABLE copy_follows ADD COLUMN slippage_cap_bps INT NULL;
UPDATE copy_follows SET slippage_cap_bps = 50;
ALTER TABLE copy_follows MODIFY slippage_cap_bps INT NOT NULL;
ALTER TABLE copy_follows ADD CONSTRAINT ck_copy_follows_slippage_cap_bps CHECK (slippage_cap_bps BETWEEN 10 AND 500);

ALTER TABLE copy_settings DROP CHECK ck_copy_settings_slippage_cap_bps;
ALTER TABLE copy_settings DROP COLUMN slippage_cap_bps;

DROP TABLE copy_market_caps;
