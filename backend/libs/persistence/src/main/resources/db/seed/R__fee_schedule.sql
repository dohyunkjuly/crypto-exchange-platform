-- Development and test data: a spot fee schedule. OrderService resolves every order against
-- the level-0 row, so an exchange with an empty fee_tiers cannot take an order; an operator
-- publishes their own ladder, which is why this is a seed and not a migration.
--
-- Whole basis points: 1 bps = 0.01%, and a negative maker fee would be a rebate. One row
-- per (product, level), edited in place (V16).
--
-- Repeatable (R__): an upsert keyed on (product, level), so an edit here lands on the next start.
INSERT INTO fee_tiers (product, level, name, min_30d_volume, maker_fee_bps, taker_fee_bps)
VALUES
    ('SPOT', 0, 'VIP 0',                   0, 10, 10),
    ('SPOT', 1, 'VIP 1',     100000000000000,  9, 10),
    ('SPOT', 2, 'VIP 2',     500000000000000,  8,  9),
    ('SPOT', 3, 'VIP 3',    2000000000000000,  7,  8),
    ('SPOT', 4, 'VIP 4',   10000000000000000,  6,  7),
    ('SPOT', 5, 'VIP 5',   50000000000000000,  5,  6),
    ('SPOT', 6, 'VIP 6',  100000000000000000,  4,  5),
    ('SPOT', 7, 'VIP 7',  250000000000000000,  3,  4),
    ('SPOT', 8, 'VIP 8',  500000000000000000,  2,  3),
    ('SPOT', 9, 'VIP 9', 1000000000000000000,  1,  2)
ON DUPLICATE KEY UPDATE
    name = VALUES(name), min_30d_volume = VALUES(min_30d_volume),
    maker_fee_bps = VALUES(maker_fee_bps), taker_fee_bps = VALUES(taker_fee_bps);
