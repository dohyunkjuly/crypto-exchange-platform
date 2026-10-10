-- Why an account is frozen, and whether it was frozen on its own or because its master was.
--
-- Freezing a master freezes its sub-accounts in the same transaction: the gate already refuses
-- a sub whose master is SUSPENDED, but a sub that reads ACTIVE while it cannot trade misleads
-- everyone who looks at it. Unfreezing the master must then release only the subs it took
-- down with it -- a sub frozen deliberately, on its own, before the master was, stays frozen.
-- frozen_with_master is how the two are told apart; a reason is the first thing anyone asks
-- about a frozen account later.
--
-- DEFAULT FALSE is for the rows that exist; the entity carries the default for new ones.

ALTER TABLE user_users
    ADD COLUMN frozen_reason      VARCHAR(255) NULL,
    ADD COLUMN frozen_with_master BOOLEAN      NOT NULL DEFAULT FALSE;
