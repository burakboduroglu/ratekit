package io.github.burakboduroglu.ratekit.ingest;

class EventPublishException extends RuntimeException {

    EventPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
