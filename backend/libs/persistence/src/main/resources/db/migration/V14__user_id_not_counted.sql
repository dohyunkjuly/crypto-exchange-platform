-- Customer ids are drawn at random by the app (RandomUidGenerator), never counted: a counter
-- tells anyone who signs up twice how many did in between. Dropping AUTO_INCREMENT makes an
-- insert that forgets the id fail, rather than quietly take a counted one. The exchange's own
-- accounts were inserted by V8, while the column still counted, and keep those ids.
--
-- MySQL refuses to modify a column that foreign keys reference unless the check is off for the
-- statement. The type and every value stay the same, so no reference can be broken by it; the
-- constraints themselves are kept and enforced again right after.

SET FOREIGN_KEY_CHECKS = 0;
ALTER TABLE user_users MODIFY id BIGINT NOT NULL;
SET FOREIGN_KEY_CHECKS = 1;
