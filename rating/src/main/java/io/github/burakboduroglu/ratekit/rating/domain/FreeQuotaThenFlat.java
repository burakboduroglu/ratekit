package io.github.burakboduroglu.ratekit.rating.domain;

import java.math.BigDecimal;

/** The first {@code freeUnits} of the period cost nothing; every unit after that costs {@code rate}. */
public record FreeQuotaThenFlat(long freeUnits, BigDecimal rate) implements PriceModel {

    public FreeQuotaThenFlat {
        if (freeUnits < 0) {
            throw new IllegalArgumentException("freeUnits must not be negative, got " + freeUnits);
        }
        PriceModel.requireValidRate(rate);
    }

    @Override
    public BigDecimal cost(long units) {
        PriceModel.requireNonNegative(units);
        long billable = Math.max(units - freeUnits, 0);
        return rate.multiply(BigDecimal.valueOf(billable));
    }
}
