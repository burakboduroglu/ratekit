package io.github.burakboduroglu.ratekit.rating.domain;

import io.github.burakboduroglu.ratekit.common.Money;

/** The priced result of one event, with the tariff version that produced it. */
public record Charge(long tariffId, Money amount) {
}
