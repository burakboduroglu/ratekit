package io.github.burakboduroglu.ratekit.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * A monetary amount in the single ratekit currency.
 *
 * <p>Every instance has scale {@value #SCALE} and is rounded {@link #ROUNDING}. Rounding happens
 * exactly where a value is produced (construction and {@link #times}), so sums of Money values are
 * exact and an invoice always equals the sum of its charges. See docs/adr/0001-money-and-rounding.md.
 */
public record Money(BigDecimal amount) implements Comparable<Money> {

    public static final int SCALE = 4;
    public static final RoundingMode ROUNDING = RoundingMode.HALF_EVEN;
    public static final Money ZERO = new Money(BigDecimal.ZERO);

    public Money {
        Objects.requireNonNull(amount, "amount");
        amount = amount.setScale(SCALE, ROUNDING);
    }

    public static Money of(String value) {
        return new Money(new BigDecimal(value));
    }

    public Money plus(Money other) {
        return new Money(amount.add(other.amount));
    }

    public Money minus(Money other) {
        return new Money(amount.subtract(other.amount));
    }

    /** Multiplies by a price factor or quantity, then rounds once. */
    public Money times(BigDecimal factor) {
        return new Money(amount.multiply(factor));
    }

    public Money times(long factor) {
        return times(BigDecimal.valueOf(factor));
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    @Override
    public int compareTo(Money other) {
        return amount.compareTo(other.amount);
    }

    @Override
    public String toString() {
        return amount.toPlainString();
    }
}
