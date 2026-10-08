package io.github.burakboduroglu.ratekit.rating.exception;

/** Kafka did not acknowledge charges or markers in time; the outbox rows stay unsent and are retried. */
public class ChargeFeedPublishException extends RuntimeException {

    public ChargeFeedPublishException(Throwable cause) {
        super("could not write to the charges topic: " + cause.getMessage(), cause);
    }
}
