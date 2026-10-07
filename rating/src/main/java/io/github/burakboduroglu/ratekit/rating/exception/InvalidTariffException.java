package io.github.burakboduroglu.ratekit.rating.exception;

/**
 * A stored tariff row cannot be turned into a price model (bad JSON, a missing or invalid field).
 * The row stays wrong until someone fixes it, so retrying cannot help; the event is dead-lettered.
 */
public class InvalidTariffException extends RuntimeException {

    public InvalidTariffException(long tariffId, String meter, Throwable cause) {
        super("tariff " + tariffId + " for meter '" + meter + "' is invalid: " + cause.getMessage(), cause);
    }
}
