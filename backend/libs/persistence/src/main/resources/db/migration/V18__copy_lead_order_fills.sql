-- What the copier needs to know about a lead order when it closes, kept on the order's own row
-- (docs/reference/copy-trading.md). The closing event carries one trade, not the order's total,
-- and the order's row in spot_orders is settlement's to write: reading it here would race it.
--
-- amount is what the fraction was a share of at placement -- the cost in the quote asset for a
-- buy, the quantity for a sell -- so a part-filled order is copied in part, and an amend
-- rescales the fraction. The two filled columns add up as trades arrive; last_sequence is what
-- makes adding safe under at-least-once delivery, as on a candle: only a later sequence counts.
--
-- The table is empty wherever this runs: nothing has traded a lead account yet.
ALTER TABLE copy_lead_orders
    ADD COLUMN amount                 BIGINT NOT NULL AFTER fraction,
    ADD COLUMN filled_quantity        BIGINT NOT NULL DEFAULT 0 AFTER amount,
    ADD COLUMN filled_quote_quantity  BIGINT NOT NULL DEFAULT 0 AFTER filled_quantity,
    ADD COLUMN last_sequence          BIGINT NOT NULL DEFAULT 0 AFTER filled_quote_quantity,
    ADD CONSTRAINT ck_copy_lead_orders_amounts CHECK (
        amount > 0 AND filled_quantity >= 0 AND filled_quote_quantity >= 0);
