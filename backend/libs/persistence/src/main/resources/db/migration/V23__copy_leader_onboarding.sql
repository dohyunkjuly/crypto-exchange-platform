-- Becoming a leader is one step (docs/reference/copy-trading.md): name, description, and the
-- money moved into the lead account, which exists and is funded from that moment. Whether staff
-- still review the application before it leads is the operator's choice, off by default; the
-- amount is held to a ceiling as well as the floor. Defaults in SQL as the rest of this row's
-- columns have them: the row is part of the schema.
ALTER TABLE copy_settings
    ADD COLUMN leaders_need_approval BOOLEAN NOT NULL DEFAULT FALSE AFTER max_copies_per_user,
    ADD COLUMN lead_max_balance BIGINT NOT NULL DEFAULT 100000000000000 AFTER lead_min_balance,
    ADD CONSTRAINT ck_copy_settings_lead_balances CHECK (lead_max_balance >= lead_min_balance);
