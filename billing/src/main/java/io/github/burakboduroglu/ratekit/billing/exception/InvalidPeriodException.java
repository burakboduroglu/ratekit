package io.github.burakboduroglu.ratekit.billing.exception;

/** The period text is not a valid year and month such as 2026-09. */
public class InvalidPeriodException extends RuntimeException {

    public InvalidPeriodException(String text) {
        super("'" + text + "' is not a valid period; use the form 2026-09");
    }
}
