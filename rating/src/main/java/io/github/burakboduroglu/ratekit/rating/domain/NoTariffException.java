package io.github.burakboduroglu.ratekit.rating.domain;

import java.time.Instant;

public class NoTariffException extends RuntimeException {

    public NoTariffException(String meter, Instant when) {
        super("no tariff for meter '" + meter + "' at " + when);
    }
}
