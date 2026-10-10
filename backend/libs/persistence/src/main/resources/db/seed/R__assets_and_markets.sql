-- Development and test data: thirteen assets and twelve USDT markets, so a fresh stack has
-- something to trade. Every number is a listing decision, which is why this is a seed and
-- not a migration: an operator lists their own markets.
--
-- Repeatable (R__): Flyway re-runs it whenever this file changes, so every statement is an
-- upsert keyed on the symbol. Edit a value here and it lands on the next start.
-- Runs only where spring.flyway.locations includes db/seed: the dev profile and the tests.
-- All amounts are scaled by 1e8: 0.01 is 1000000, 10 USDT is 1000000000.

-- display_decimals is presentation only; balances are stored at 1e8 regardless. Each is the
-- asset's native precision, capped at 8. USDT shows six because six is its precision on
-- both Ethereum and Tron, and two more digits could never be anything but zero.
INSERT INTO wallet_assets (symbol, name, asset_type, display_decimals,
                           trading_enabled, deposit_enabled, withdrawal_enabled)
VALUES
    ('BTC',  'Bitcoin',      'CRYPTO', 8, TRUE, TRUE, TRUE),
    ('ETH',  'Ethereum',     'CRYPTO', 8, TRUE, TRUE, TRUE),
    ('USDT', 'Tether',       'CRYPTO', 6, TRUE, TRUE, TRUE),
    ('SOL',  'Solana',       'CRYPTO', 8, TRUE, TRUE, TRUE),
    ('XRP',  'XRP',          'CRYPTO', 6, TRUE, TRUE, TRUE),
    ('DOGE', 'Dogecoin',     'CRYPTO', 8, TRUE, TRUE, TRUE),
    ('ADA',  'Cardano',      'CRYPTO', 6, TRUE, TRUE, TRUE),
    ('AVAX', 'Avalanche',    'CRYPTO', 8, TRUE, TRUE, TRUE),
    ('LINK', 'Chainlink',    'CRYPTO', 8, TRUE, TRUE, TRUE),
    ('DOT',  'Polkadot',     'CRYPTO', 8, TRUE, TRUE, TRUE),
    ('TRX',  'TRON',         'CRYPTO', 6, TRUE, TRUE, TRUE),
    ('LTC',  'Litecoin',     'CRYPTO', 8, TRUE, TRUE, TRUE),
    ('BCH',  'Bitcoin Cash', 'CRYPTO', 8, TRUE, TRUE, TRUE)
ON DUPLICATE KEY UPDATE
    name = VALUES(name), asset_type = VALUES(asset_type), display_decimals = VALUES(display_decimals),
    trading_enabled = VALUES(trading_enabled), deposit_enabled = VALUES(deposit_enabled),
    withdrawal_enabled = VALUES(withdrawal_enabled);

-- One row per market. Asset ids are AUTO_INCREMENT, so the pair is resolved by symbol.
--
-- Tick and step: the tick quotes the asset's typical price to about five significant
-- figures, and the step makes one increment worth a fraction of a cent rather than a
-- fraction of a dollar. That is why DOGE steps in whole coins while BCH steps in
-- ten-thousandths. BTC and ETH use the filters the market already expects of them.
--
-- Market-order bounds: a limit order for the whole maximum rests, a market order for it
-- sweeps every level, so the market maximum sits around 1-2% of the limit one. The market
-- minimum is the dust floor for a market sell, which has no notional check; each clears
-- the 10 USDT minimum at a price well below today's.
--
-- Depth groupings: chosen so twenty grouped rows span roughly a tenth of a percent up to a
-- couple of percent of the price. A fixed multiple of the tick would give DOGE a coarsest
-- grouping worth 7% of its price, one row swallowing the whole visible book.
--
-- Command partitions: BTC and ETH on partitions of their own, the rest spread over 2-7
-- (docs/reference/command-partition.md). Fixed once a market has traded.
--
-- Left NULL on purpose: price_band_bps, because nothing reads it yet and a value would
-- look enforced while enforcing nothing; market_qty_step, because the limit step is right
-- for both.
INSERT INTO spot_symbols (symbol, base_asset_id, quote_asset_id, command_partition, status,
                          price_tick, depth_groupings, qty_step, min_qty, max_qty,
                          market_min_qty, market_max_qty,
                          min_notional, max_notional, min_quote_qty, max_quote_qty,
                          market_orders_enabled, stop_orders_enabled)
SELECT CONCAT(m.base, 'USDT'), base.id, quote.id, m.partition_no, 'TRADING',
       m.price_tick, m.groupings, m.qty_step, m.min_qty, m.max_qty,
       m.market_min_qty, m.market_max_qty,
       1000000000, m.max_notional, 1000000000, m.max_notional,
       TRUE, TRUE
FROM (
    --                                       tick      step      min_qty           max_qty       mkt_min       mkt_max        max_notional  partition
    SELECT 'BTC' AS base, 1000000 AS price_tick, 1000 AS qty_step, 1000 AS min_qty, 900000000000 AS max_qty,
           20000 AS market_min_qty, 10000000000 AS market_max_qty, 1000000000000000 AS max_notional, 0 AS partition_no,
           '1000000,10000000,100000000,1000000000,5000000000,10000000000' AS groupings
    UNION ALL SELECT 'ETH',  1000000,     10000,     10000,     900000000000,      500000,     200000000000, 1000000000000000, 1, '1000000,10000000,100000000,500000000,1000000000'
    UNION ALL SELECT 'SOL',  1000000,    100000,    100000,  900000000000000,    10000000,    5000000000000,  100000000000000, 2, '1000000,10000000,100000000,500000000'
    UNION ALL SELECT 'XRP',    10000, 100000000, 100000000,  900000000000000,  2000000000,  500000000000000,  100000000000000, 2, '10000,100000,1000000,10000000'
    UNION ALL SELECT 'DOGE',    1000, 100000000, 100000000,  900000000000000, 10000000000,  500000000000000,  100000000000000, 3, '1000,10000,100000,1000000'
    UNION ALL SELECT 'ADA',    10000, 100000000, 100000000,  900000000000000,  3000000000,  500000000000000,  100000000000000, 3, '10000,100000,1000000,10000000'
    UNION ALL SELECT 'AVAX',  100000,   1000000,   1000000,  900000000000000,   100000000,   10000000000000,  100000000000000, 4, '100000,1000000,10000000,100000000'
    UNION ALL SELECT 'BCH',  1000000,     10000,     10000,  900000000000000,     5000000,     300000000000,  100000000000000, 4, '1000000,10000000,100000000,1000000000'
    UNION ALL SELECT 'DOT',   100000,   1000000,   1000000,  900000000000000,   500000000,   30000000000000,  100000000000000, 5, '100000,1000000,10000000'
    UNION ALL SELECT 'LINK',  100000,   1000000,   1000000,  900000000000000,   100000000,   20000000000000,  100000000000000, 5, '100000,1000000,10000000,100000000'
    UNION ALL SELECT 'LTC',  1000000,    100000,    100000,  900000000000000,    20000000,    2000000000000,  100000000000000, 6, '1000000,10000000,100000000,500000000'
    UNION ALL SELECT 'TRX',     1000,  10000000,  10000000,  900000000000000, 10000000000,  500000000000000,  100000000000000, 7, '1000,10000,100000,1000000'
) m
JOIN wallet_assets base  ON base.symbol  = m.base
JOIN wallet_assets quote ON quote.symbol = 'USDT'
ON DUPLICATE KEY UPDATE
    price_tick = VALUES(price_tick), depth_groupings = VALUES(depth_groupings),
    qty_step = VALUES(qty_step), min_qty = VALUES(min_qty), max_qty = VALUES(max_qty),
    market_min_qty = VALUES(market_min_qty), market_max_qty = VALUES(market_max_qty),
    min_notional = VALUES(min_notional), max_notional = VALUES(max_notional),
    min_quote_qty = VALUES(min_quote_qty), max_quote_qty = VALUES(max_quote_qty),
    market_orders_enabled = VALUES(market_orders_enabled), stop_orders_enabled = VALUES(stop_orders_enabled);
