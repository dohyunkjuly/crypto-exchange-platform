package com.exchange.common.money;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void convertsDecimalStringToScaledWireValue() {
        assertEquals(150_000_000L, Money.toWire("1.5"));
        assertEquals(1L, Money.toWire("0.00000001"));
        assertEquals(6_325_000_000_000L, Money.toWire("63250"));
    }

    @Test
    void roundTripsWithoutLoss() {
        assertEquals("1.5", Money.toDecimal(Money.toWire("1.5")));
        assertEquals("0.00000001", Money.toDecimal(Money.toWire("0.00000001")));
    }

    @Test
    void refusesToSilentlyRoundAwayPrecision() {
        // A ninth decimal place cannot be represented. Truncating it would mean
        // quietly filling an order at a price the user did not ask for.
        assertThrows(IllegalArgumentException.class, () -> Money.toWire("0.000000001"));
    }
}
