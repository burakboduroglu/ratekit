package io.github.burakboduroglu.ratekit.rating.exception;

import java.time.Instant;

/**
 * A new tariff version may not start before now: events up to now were already priced with the old
 * version, and a version reaching back would price the rest of that time differently.
 */
public class TariffInThePastException extends RuntimeException {

    public TariffInThePastException(Instant effectiveFrom, Instant now) {
        super("effectiveFrom " + effectiveFrom + " is in the past (now " + now + "); a tariff cannot change prices retroactively");
    }
}
