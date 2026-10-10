package com.exchange.spot.symbol;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SpotSymbolTest {

    private static final long ONE = 100_000_000L;

    /** BTCUSDT: 0.01 tick, 0.00001 step, 0.0001 min, 9000 max, 10 USDT min notional. */
    private static SpotSymbol btcusdt() {
        return new SpotSymbol("BTCUSDT", 1L, 2L,
                1_000_000L, 1_000L, 10_000L, 9_000 * ONE, 10 * ONE, 0);
    }

    @Test
    void a_price_off_the_tick_is_rejected() {
        SpotSymbol s = btcusdt();
        assertTrue(s.isPriceOnTick(6_325_000_000_000L));   // 63250.00
        assertFalse(s.isPriceOnTick(6_325_000_000_001L));  // 63250.00000001
    }

    @Test
    void notional_uses_overflow_safe_arithmetic() {
        SpotSymbol s = btcusdt();
        // 63250 * 1.5 = 94875, which the naive expression could not compute at all.
        assertTrue(s.meetsNotional(63_250 * ONE, 150_000_000L));
        // 0.0001 BTC at 63250 is 6.325 USDT, under the 10 USDT floor.
        assertFalse(s.meetsNotional(63_250 * ONE, 10_000L));
    }

    @Test
    @DisplayName("without market bounds, a market order uses the limit-order rule")
    void market_falls_back_to_limit_bounds() {
        SpotSymbol s = btcusdt();
        assertTrue(s.isValidQuantity(true, ONE));
        assertFalse(s.isValidQuantity(true, 10_000 * ONE));   // over maxQty
    }

    @Test
    @DisplayName("market bounds are tighter, because a market order sweeps the book")
    void market_bounds_override_when_set() {
        SpotSymbol s = btcusdt();
        s.setMarketMaxQty(10 * ONE);

        assertTrue(s.isValidQuantity(false, 100 * ONE));    // fine as a resting limit
        assertFalse(s.isValidQuantity(true, 100 * ONE));    // refused as a market sweep
        assertTrue(s.isValidQuantity(true, 5 * ONE));
    }

    @Test
    void quote_sized_orders_are_bounded_separately() {
        SpotSymbol s = btcusdt();
        assertTrue(s.isValidQuoteQuantity(100 * ONE));      // no bounds configured

        s.setMinQuoteQty(10 * ONE);
        s.setMaxQuoteQty(1_000 * ONE);
        assertFalse(s.isValidQuoteQuantity(5 * ONE));
        assertTrue(s.isValidQuoteQuantity(100 * ONE));
        assertFalse(s.isValidQuoteQuantity(5_000 * ONE));
        assertFalse(s.isValidQuoteQuantity(0));
    }
}
