-- Staff no longer approve leaders: a customer leads the moment they fund. Rows waiting for a
-- decision lead now if they were funded, and a turned-down or unfunded one is closed, so its
-- customer can lead again. APPLIED and REJECTED stay in the enum; stored values are never removed.
UPDATE copy_leaders SET status = 'ACTIVE' WHERE status = 'APPLIED' AND lead_user_id IS NOT NULL;
UPDATE copy_leaders SET status = 'CLOSED' WHERE status IN ('APPLIED', 'REJECTED');

ALTER TABLE copy_settings DROP COLUMN leaders_need_approval;
