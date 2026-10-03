package io.github.burakboduroglu.ratekit.ingest.exception;

/** Kafka did not acknowledge the write; the caller should retry. */
public class EventPublishException extends RuntimeException {

    public EventPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
