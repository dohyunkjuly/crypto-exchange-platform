-- The admin console searches every customer's orders, newest first (docs/reference/admin.md).
-- Every index spot_orders had led with a user or a symbol, so a search across all of them was a
-- full scan and a sort. created_at never changes after insert, so this costs the insert and
-- nothing else: settlement's updates on every fill do not touch it.
CREATE INDEX idx_spot_orders_created ON spot_orders (created_at);
