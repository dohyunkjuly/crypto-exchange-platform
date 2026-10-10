package com.exchange.common.money;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/**
 * Converts between the decimal strings the REST API speaks ("63250.00") and the scaled
 * int64 the wire format and engine speak (6325000000000).
 *
 * <p>The class exists to enforce one rule (AGENTS.md): money never touches a
 * {@code double}. Every conversion goes through {@link BigDecimal}, and anything that
 * would silently lose precision throws instead.
 */
public final class Money {

    /** Wire values are scaled by 1e8, matching proto/exchange/spot/v1/common.proto. */
    public static final int SCALE = 8;

    /** The same factor as a long, for the integer arithmetic below. */
    public static final long SCALE_FACTOR = 100_000_000L;

    private static final BigDecimal MULTIPLIER = BigDecimal.TEN.pow(SCALE);

    private static final BigInteger BIG_SCALE_FACTOR = BigInteger.valueOf(SCALE_FACTOR);

    private Money() {
    }

    /**
     * Multiplies two scaled values, returning a scaled result: {@code a * b / 1e8}.
     *
     * <p><b>Do not write {@code price * quantity / 100_000_000L} by hand.</b> Both operands
     * are already scaled, so the product needs 128 bits before the division brings it back:
     *
     * <pre>
     *   6_325_000_000_000  ×  100_000_000  =  632_500_000_000_000_000_000
     *   Long.MAX_VALUE                     =    9_223_372_036_854_775_807
     * </pre>
     *
     * <p>It wraps silently to a negative number -- wrong for any trade above ~0.015 BTC.
     * Fast path: a whole number of units divides first, exactly and without allocating.
     */
    public static long multiply(long a, long b) {
        if (a % SCALE_FACTOR == 0) {
            return Math.multiplyExact(a / SCALE_FACTOR, b);
        }
        if (b % SCALE_FACTOR == 0) {
            return Math.multiplyExact(a, b / SCALE_FACTOR);
        }
        // Room for the product only if the two magnitudes together fit in 63 bits.
        if (Long.numberOfLeadingZeros(Math.abs(a)) + Long.numberOfLeadingZeros(Math.abs(b)) > 64) {
            return a * b / SCALE_FACTOR;
        }
        return BigInteger.valueOf(a)
                .multiply(BigInteger.valueOf(b))
                .divide(BIG_SCALE_FACTOR)
                .longValueExact();
    }

    /**
     * Divides two scaled values, returning a scaled result: {@code a * 1e8 / b}. Used for
     * average fill price, where the numerator has already been scaled once.
     *
     * @throws ArithmeticException if {@code b} is zero
     */
    public static long divide(long a, long b) {
        if (b == 0) {
            throw new ArithmeticException("division by zero");
        }
        if (Long.numberOfLeadingZeros(Math.abs(a)) > 27) {
            return a * SCALE_FACTOR / b;
        }
        return BigInteger.valueOf(a)
                .multiply(BIG_SCALE_FACTOR)
                .divide(BigInteger.valueOf(b))
                .longValueExact();
    }

    /**
     * Applies a rate in basis points to a scaled amount. 1 bps = 0.01%, so 10 bps of 100
     * is 0.1.
     *
     * <p>Signed, because a negative maker fee is a rebate. Truncates toward zero, which
     * for a fee means the platform never rounds up against the user.
     */
    public static long applyBps(long amount, int bps) {
        if (Long.numberOfLeadingZeros(Math.abs(amount)) > 18) {
            return amount * bps / 10_000L;
        }
        return BigInteger.valueOf(amount)
                .multiply(BigInteger.valueOf(bps))
                .divide(BigInteger.valueOf(10_000L))
                .longValueExact();
    }

    /**
     * Parses a decimal string into its scaled int64 wire representation.
     *
     * @throws IllegalArgumentException if the value has more than {@value #SCALE}
     *                                  decimal places, rather than rounding it away
     *                                  behind the user's back
     */
    public static long toWire(String decimal) {
        BigDecimal parsed = new BigDecimal(decimal);
        if (parsed.stripTrailingZeros().scale() > SCALE) {
            throw new IllegalArgumentException(
                    "value " + decimal + " has more than " + SCALE + " decimal places");
        }
        return parsed.multiply(MULTIPLIER).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
    }

    /** Renders a scaled int64 wire value back to a decimal string for clients. */
    public static String toDecimal(long wire) {
        return BigDecimal.valueOf(wire).divide(MULTIPLIER).toPlainString();
    }
}
