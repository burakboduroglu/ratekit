package io.github.burakboduroglu.ratekit.ingest.exception;

/** The usage time is outside the window ingest accepts: too far in the future, or in a closed month. */
public class EventTimeOutOfRangeException extends RuntimeException {

    public EventTimeOutOfRangeException(String message) {
        super(message);
    }
}
