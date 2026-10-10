package com.exchange.copy.stats;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * A lead account's return over a run of snapshots (docs/reference/copy-trading.md).
 *
 * <p>Each step is what the account became against what it was, with the money moved in or out
 * in between taken off first; the steps are multiplied. So adding funds is not a gain, taking
 * them out is not a loss, and a leader who doubles 100 and then adds 10,000 still shows the
 * double. A ratio, not money: nothing here is an amount anyone is owed.
 */
public final class ReturnMath {

    /** An account at one moment, and what was moved in (minus out) since the point before. */
    public record Point(long equity, long netTransfers) {
    }

    private static final BigDecimal BPS = BigDecimal.valueOf(10_000);
    /** A column is an INT; a return past this is a data fault, not a number to show. */
    private static final BigDecimal LIMIT = BigDecimal.valueOf(1_000_000_000);

    private ReturnMath() {
    }

    /**
     * The account's growth after each point, as a multiple of where it started: 1 at the
     * first point, then the steps multiplied. A step from an empty account is skipped, so the
     * list is as long as the points and a flat stretch reads as flat.
     *
     * @param points oldest first
     * @return empty for no points
     */
    public static List<BigDecimal> growth(List<Point> points) {
        List<BigDecimal> out = new ArrayList<>(points.size());
        BigDecimal growth = BigDecimal.ONE;
        for (int i = 0; i < points.size(); i++) {
            if (i > 0 && points.get(i - 1).equity() > 0) {
                long after = points.get(i).equity() - points.get(i).netTransfers();
                growth = growth.multiply(BigDecimal.valueOf(Math.max(after, 0))
                        .divide(BigDecimal.valueOf(points.get(i - 1).equity()), MathContext.DECIMAL64), MathContext.DECIMAL64);
            }
            out.add(growth);
        }
        return out;
    }

    /**
     * @param points oldest first; the first is only what the second is measured against
     * @return basis points, or null when no step can be measured: fewer than two points, or
     *         nothing in the account to grow
     */
    public static Integer chainedBps(List<Point> points) {
        if (!measurable(points)) {
            return null;
        }
        List<BigDecimal> growth = growth(points);
        return bps(growth.get(growth.size() - 1).subtract(BigDecimal.ONE));
    }

    /**
     * The deepest fall from a high point to a later low, as positive basis points: how much a
     * follower who joined at the worst moment would have been down. Null with nothing measured.
     */
    public static Integer maxDrawdownBps(List<Point> points) {
        if (!measurable(points)) {
            return null;
        }
        BigDecimal peak = null;
        BigDecimal worst = BigDecimal.ZERO;
        for (BigDecimal g : growth(points)) {
            peak = peak == null ? g : peak.max(g);
            if (peak.signum() > 0) {
                worst = worst.max(peak.subtract(g).divide(peak, MathContext.DECIMAL64));
            }
        }
        return bps(worst);
    }

    /**
     * What the account earned over the points, in its own units: each step's change with the
     * money moved in or out taken off. Null with nothing measured.
     */
    public static Long earned(List<Point> points) {
        if (!measurable(points)) {
            return null;
        }
        long earned = 0;
        for (int i = 1; i < points.size(); i++) {
            if (points.get(i - 1).equity() > 0) {
                earned += points.get(i).equity() - points.get(i).netTransfers() - points.get(i - 1).equity();
            }
        }
        return earned;
    }

    private static boolean measurable(List<Point> points) {
        for (int i = 1; i < points.size(); i++) {
            if (points.get(i - 1).equity() > 0) {
                return true;
            }
        }
        return false;
    }

    private static int bps(BigDecimal ratio) {
        return ratio.multiply(BPS).setScale(0, RoundingMode.HALF_EVEN).min(LIMIT).intValueExact();
    }
}
