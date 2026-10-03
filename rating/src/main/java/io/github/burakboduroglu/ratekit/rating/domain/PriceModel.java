package io.github.burakboduroglu.ratekit.rating.domain;

import java.math.BigDecimal;

/**
 * Strategy for one way of pricing a meter.
 *
 * <p>A model answers a single question: what is the total cost of {@code units} consumed in the
 * current period? The result is exact and unrounded; rounding is applied once, by {@link Rater}.
 */
public interface PriceModel {

    /** Exact cumulative cost of {@code units} consumed so far in the period. {@code units >= 0}. */
    BigDecimal cost(long units);

    /** Shared guard for implementations. */
    static void requireNonNegative(long units) {
        if (units < 0) {
            throw new IllegalArgumentException("units must not be negative, got " + units);
        }
    }

    static void requireValidRate(BigDecimal rate) {
        if (rate == null || rate.signum() < 0) {
            throw new IllegalArgumentException("rate must be zero or positive, got " + rate);
        }
    }
}
