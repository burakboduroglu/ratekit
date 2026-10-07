package io.github.burakboduroglu.ratekit.rating.repository;

import java.time.Instant;

/** A tariff version as stored, with its parameters still as JSON text. */
public record TariffRow(long id, String meter, String model, Instant effectiveFrom, String paramsJson) {
}
