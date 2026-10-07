package io.github.burakboduroglu.ratekit.rating.exception;

import java.time.Instant;

public class TariffVersionExistsException extends RuntimeException {

    public TariffVersionExistsException(String meter, Instant effectiveFrom) {
        super("meter '" + meter + "' already has a tariff version starting at " + effectiveFrom);
    }
}
