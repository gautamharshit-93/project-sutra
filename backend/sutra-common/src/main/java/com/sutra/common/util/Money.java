package com.sutra.common.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Decimal helpers. All money is BigDecimal, rounded HALF_UP to 2 places at the end of each calculation. */
public final class Money {

    public static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    /** Absolute tolerance when comparing a stated amount with a calculated one (rounding on vendor documents). */
    public static final BigDecimal ABSOLUTE_TOLERANCE = new BigDecimal("0.05");
    /** Relative tolerance (0.1%) for large amounts. */
    public static final BigDecimal RELATIVE_TOLERANCE = new BigDecimal("0.001");

    private Money() {
    }

    public static BigDecimal round(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    public static BigDecimal percentOf(BigDecimal base, BigDecimal percent) {
        return base.multiply(percent).divide(HUNDRED, 6, RoundingMode.HALF_UP);
    }

    /** True when {@code a} and {@code b} are equal within the absolute or relative tolerance. */
    public static boolean approximatelyEqual(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) {
            return false;
        }
        BigDecimal diff = a.subtract(b).abs();
        if (diff.compareTo(ABSOLUTE_TOLERANCE) <= 0) {
            return true;
        }
        BigDecimal scale = a.abs().max(b.abs());
        return diff.compareTo(scale.multiply(RELATIVE_TOLERANCE)) <= 0;
    }

    /** (value - base) / base * 100, or null when base is zero/null. */
    public static BigDecimal percentDifference(BigDecimal value, BigDecimal base) {
        if (value == null || base == null || base.signum() == 0) {
            return null;
        }
        return value.subtract(base).multiply(HUNDRED).divide(base, 2, RoundingMode.HALF_UP);
    }
}
