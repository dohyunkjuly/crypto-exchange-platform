-- The API's commands to the engine, written in the same transaction as the order they
-- describe and published afterwards. Why: a publish inside the transaction cannot be rolled
-- back with it. A publish that timed out but landed anyway left the engine trading an order
-- MySQL had rolled back -- no row, no lock, and settlement stuck on the row it could not find.
-- docs/reference/delivery-guarantees.md.
--
-- The serialized command, not the fields to rebuild it from: an amend's new terms are not on
-- spot_orders until settlement applies them, and a re-send must carry the SAME command_id or
-- the engine cannot recognise it as a repeat.
--
-- Rows are published within milliseconds and swept out after an hour; this table is small
-- unless Kafka is down, in which case it is the queue.
CREATE TABLE spot_command_outbox (
    -- The command's own id, so a row and the command it carries are the same thing.
    id                 BINARY(16)      NOT NULL PRIMARY KEY,

    symbol             VARCHAR(20)     NOT NULL,
    command_partition  INT             NOT NULL,

    -- NULL for a cancel-all, which names a user, not an order. No foreign key: nothing joins
    -- to this table, and an FK would only take a lock on the order row at every insert.
    order_id           BINARY(16)      NULL,
    user_id            BIGINT          NOT NULL,

    payload            VARBINARY(2048) NOT NULL,

    created_at         TIMESTAMP(6)    NOT NULL,
    published_at       TIMESTAMP(6)    NULL
);

-- The sweeper: what is still unpublished, oldest first.
CREATE INDEX idx_spot_command_outbox_pending ON spot_command_outbox (published_at, created_at);

-- A cancel or amend asking whether the order's own place is still on its way.
CREATE INDEX idx_spot_command_outbox_order ON spot_command_outbox (order_id, published_at);

-- A cancel-all asking the same of everything the user has.
CREATE INDEX idx_spot_command_outbox_user ON spot_command_outbox (user_id, published_at);
