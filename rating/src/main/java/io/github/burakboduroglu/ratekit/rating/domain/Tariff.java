package io.github.burakboduroglu.ratekit.rating.domain;

import java.time.Instant;
import java.util.Objects;

/** One version of a meter's pricing, valid from {@code effectiveFrom} (inclusive) until a later version starts. */
public record Tariff(long id, String meter, Instant effectiveFrom, PriceModel model) {

    public Tariff {
        Objects.requireNonNull(meter, "meter");
        Objects.requireNonNull(effectiveFrom, "effectiveFrom");
        Objects.requireNonNull(model, "model");
    }
}
