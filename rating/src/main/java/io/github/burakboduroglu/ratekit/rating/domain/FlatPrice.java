package io.github.burakboduroglu.ratekit.rating.domain;

import java.math.BigDecimal;

/** Every unit costs the same. */
public record FlatPrice(BigDecimal rate) implements PriceModel {

    public FlatPrice {
        PriceModel.requireValidRate(rate);
    }

    @Override
    public BigDecimal cost(long units) {
        PriceModel.requireNonNegative(units);
        return rate.multiply(BigDecimal.valueOf(units));
    }
}
