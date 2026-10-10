package com.exchange.common.money;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MoneyArithmeticTest {

    private static final long ONE = Money.SCALE_FACTOR;

    @Test
    @DisplayName("the naive expression overflows; multiply does not")
    void one_btc_at_63250() {
        long price = 63_250 * ONE;
        long quantity = ONE;

        // What price * quantity / 1e8 does by hand. Overflow wraps modulo 2^64, so the
        // result is not even reliably negative -- it is simply, quietly, a different
        // number. Here it lands on 53,107,014,938 instead of 63,250.
        long naive = price * quantity / ONE;
        assertTrue(naive != 63_250 * ONE, "the naive expression should be visibly broken");
        assertEquals(53_107_014_938L, naive);

        assertEquals(63_250 * ONE, Money.multiply(price, quantity));
    }

    @Test
    void fractional_operands_take_the_slow_path_and_stay_exact() {
        long price = 6_325_050_000_000L;      // 63,250.50
        long quantity = 150_000_000L;         // 1.5

        // 63250.50 * 1.5 = 94875.75
        assertEquals(9_487_575_000_000L, Money.multiply(price, quantity));
    }

    @Test
    void multiply_matches_bigInteger_across_a_wide_range() {
        // Combinations whose result is actually representable. A scaled long tops out
        // around 92 billion units, so price x quantity has to stay under that.
        long[] prices = {1, ONE, 63_250 * ONE, 6_325_050_000_000L, 1_000_000 * ONE};
        long[] quantities = {1, 12_345_678L, ONE, 150_000_000L, 10_000 * ONE};

        for (long p : prices) {
            for (long q : quantities) {
                long expected = BigInteger.valueOf(p).multiply(BigInteger.valueOf(q))
                        .divide(BigInteger.valueOf(ONE)).longValueExact();
                assertEquals(expected, Money.multiply(p, q), p + " * " + q);
            }
        }
    }

    @Test
    void multiply_throws_rather_than_wrapping_when_the_result_cannot_fit() {
        assertThrows(ArithmeticException.class,
                () -> Money.multiply(Long.MAX_VALUE / 2, Long.MAX_VALUE / 2));
    }

    @Test
    void divide_is_the_inverse_of_multiply() {
        long price = 6_325_050_000_000L;
        long quantity = 150_000_000L;
        long quote = Money.multiply(price, quantity);

        assertEquals(price, Money.divide(quote, quantity));
    }

    @Test
    void divide_by_zero_throws() {
        assertThrows(ArithmeticException.class, () -> Money.divide(ONE, 0));
    }

    @Test
    void bps_of_a_large_notional_stays_exact() {
        long tenMillion = 10_000_000 * ONE;

        // The naive form overflows past roughly $9.2M.
        assertEquals(10_000 * ONE, Money.applyBps(tenMillion, 10));   // 10 bps = 0.1%
    }

    @Test
    void a_negative_bps_is_a_rebate() {
        long notional = 100_000 * ONE;
        assertEquals(-100 * ONE, Money.applyBps(notional, -10));
    }

    @Test
    void bps_truncates_toward_zero_so_the_platform_never_rounds_up_against_the_user() {
        assertEquals(0, Money.applyBps(999, 1));   // 0.0999... → 0
    }
}
