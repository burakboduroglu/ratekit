package io.github.burakboduroglu.ratekit.rating.exception;

import java.time.Duration;
import java.time.Instant;

/**
 * A new tariff version may not start before every rating instance's cache has expired (one TTL after
 * now): until then an instance that did not take the request can still price with the old versions
 * (ADR 0012). Starting in the past is the same rule with a negative margin (ADR 0009).
 */
public class TariffStartsTooSoonException extends RuntimeException {

    public TariffStartsTooSoonException(Instant effectiveFrom, Instant earliest, Duration ttl) {
        super("effectiveFrom " + effectiveFrom + " is too early; a tariff version must start at least " + ttl
                + " from now, at " + earliest + " or later, so every rating instance's cache has expired before it applies"
                + " and a tariff cannot change prices retroactively");
    }
}
