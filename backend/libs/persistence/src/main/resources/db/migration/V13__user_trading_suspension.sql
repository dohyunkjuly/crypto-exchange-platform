-- Trading suspended by an admin, the account otherwise untouched: it still logs in, reads, and
-- cancels what it has on the book. Suspended while trading_suspended_at is set; the reason is
-- the first thing anyone asks about it later. A master's suspension covers its sub-accounts
-- through the trading gate, so no sub row is written.

ALTER TABLE user_users
    ADD COLUMN trading_suspended_at     TIMESTAMP(6) NULL,
    ADD COLUMN trading_suspended_reason VARCHAR(255) NULL;
