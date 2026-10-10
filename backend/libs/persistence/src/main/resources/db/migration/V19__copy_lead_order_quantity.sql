-- How the copier knows a lead order has filled (docs/reference/copy-trading.md).
--
-- The engine stamps every trade of a taker with that order's final status, so "this trade says
-- FILLED" is true of the first of five as much as the last. What does say it is arithmetic: the
-- order has filled when what filled covers what was ordered. amount cannot be that measure for a
-- limit buy -- it is the cost at the limit price, and a buy that improves on its price spends
-- less -- so the quantity ordered is kept beside it. Zero for an order sized in the quote asset,
-- which is filled when its quote amount is spent.
ALTER TABLE copy_lead_orders
    ADD COLUMN quantity BIGINT NOT NULL DEFAULT 0 AFTER amount,
    ADD CONSTRAINT ck_copy_lead_orders_quantity CHECK (quantity >= 0);
