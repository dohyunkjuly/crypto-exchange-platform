-- An API key's id is a ULID, like an order's, rather than a counter: the web's key list and the
-- URLs that edit and revoke a key carry it, and a counter there tells anyone who makes two keys
-- how many the exchange issued in between. Nothing references user_api_keys.id, so the keys
-- that exist are given one here and the column swapped in place. They are drawn at random; only
-- keys made from now on carry their creation time in the id.

ALTER TABLE user_api_keys ADD COLUMN new_id BINARY(16) NULL FIRST;
UPDATE user_api_keys SET new_id = RANDOM_BYTES(16);
-- AUTO_INCREMENT must go before the primary key can: MySQL keeps it on a key.
ALTER TABLE user_api_keys MODIFY id BIGINT NOT NULL;
ALTER TABLE user_api_keys DROP PRIMARY KEY, DROP COLUMN id;
ALTER TABLE user_api_keys CHANGE COLUMN new_id id BINARY(16) NOT NULL FIRST, ADD PRIMARY KEY (id);
