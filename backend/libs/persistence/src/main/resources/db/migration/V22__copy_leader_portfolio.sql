-- Whether a leader's profile shows what the lead account holds and has traded
-- (docs/reference/copy-trading.md). The figures are always public; the positions are the
-- leader's to show. Existing rows take the column's zero; none exist outside a developer's
-- machine, and the entity's default for a new leader is to show.
ALTER TABLE copy_leaders
    ADD COLUMN show_portfolio BOOLEAN NOT NULL AFTER bio;
