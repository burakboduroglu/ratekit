package io.github.burakboduroglu.ratekit.common;

/** Kafka topic names shared by the services. */
public final class Topics {

    public static final String USAGE_EVENTS = "usage-events";

    /** Events that could not be processed, kept with the reason so they can be inspected and replayed. */
    public static final String USAGE_EVENTS_DLQ = "usage-events.dlq";

    private Topics() {
    }
}
