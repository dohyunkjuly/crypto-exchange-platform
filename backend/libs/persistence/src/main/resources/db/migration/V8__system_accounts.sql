-- The exchange's own two accounts (docs/reference/treasury.md).
--
-- A migration rather than a seed, unlike the markets and the fee schedule: every deployment
-- has exactly these two, the ledger references their ids forever, and a re-runnable script
-- that could recreate them differently is the one thing that must not happen to them.
--
-- fees@exchange holds what trading earned: credited every fee, debited every rebate. Nothing
-- is paid out of it -- it is swept to treasury first, so its ledger reads as fees in, rebates
-- out, sweeps out, and nothing else.
--
-- treasury@exchange is what the house holds to work with: what has been swept, what has been
-- funded by hand, and later the convert inventory (docs/reference/convert.md's house).
--
-- Neither can log in: User.canLogIn() requires PRIMARY, so the type refuses them whatever the
-- password column holds. The null password is belt and braces, not the rule.

ALTER TABLE user_users DROP CHECK ck_user_users_type;
ALTER TABLE user_users ADD CONSTRAINT ck_user_users_type
    CHECK (type IN ('PRIMARY', 'SUB', 'SYSTEM'));

-- email_verified FALSE and kyc_level NONE are the honest values: neither is true of these
-- rows, and nothing should ever read them as a reason to allow something.
INSERT INTO user_users (email, email_verified, password_hash, name, status, kyc_level, mfa_enabled, type)
VALUES ('fees@exchange',     FALSE, NULL, 'Fee revenue', 'ACTIVE', 'NONE', FALSE, 'SYSTEM'),
       ('treasury@exchange', FALSE, NULL, 'Treasury',    'ACTIVE', 'NONE', FALSE, 'SYSTEM');
