-- The admin orders page filtered to one market and a date range. Its only symbol-led index was
-- (symbol_id, status), which has no time in it, so "BTC/USDT last January" walked every order
-- the market ever had and sorted. created_at never changes after insert, so this costs the
-- insert and nothing else, like idx_spot_orders_created (V11). A customer's own history still
-- rides (user_id, created_at): this is for the one reader that has no user.
CREATE INDEX idx_spot_orders_symbol_created ON spot_orders (symbol_id, created_at);
