-- One row per (product, level), edited in place. The effective window was never read: the fee
-- lookup finds a tier by product and level alone, so a second version of a level would have
-- broken every order rather than scheduled a change. What a customer was charged is kept where
-- it matters, on the order itself (maker_fee_bps, taker_fee_bps, stamped at acceptance), and a
-- change to the schedule is kept in the admin audit log.

ALTER TABLE fee_tiers
    DROP INDEX uq_fee_tiers_product_level_from,
    DROP COLUMN effective_from,
    DROP COLUMN effective_to,
    ADD CONSTRAINT uq_fee_tiers_product_level UNIQUE (product, level);
