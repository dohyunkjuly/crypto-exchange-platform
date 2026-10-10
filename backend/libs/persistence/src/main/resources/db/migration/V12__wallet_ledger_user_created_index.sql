-- One customer's statement across every asset, newest first: the admin console's ledger tab.
-- V2's (user_id, asset_id, created_at) serves one asset only; across assets it would sort every
-- row the customer has, and a market maker has millions. The cost is one more index on the
-- ledger's insert path.
CREATE INDEX idx_wallet_ledger_entries_user_created ON wallet_ledger_entries (user_id, created_at);
