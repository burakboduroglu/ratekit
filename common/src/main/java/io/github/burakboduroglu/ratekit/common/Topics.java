package io.github.burakboduroglu.ratekit.common;

/** Kafka topic names shared by the services. */
public final class Topics {

    public static final String USAGE_EVENTS = "usage-events";

    /** Events that could not be processed, kept with the reason so they can be inspected and replayed. */
    public static final String USAGE_EVENTS_DLQ = "usage-events.dlq";

    /** Charges rating stored, relayed from its outbox to billing and keyed by account, plus progress markers (ADR 0019). */
    public static final String CHARGES = "charges";

    /**
     * Type names in the {@code __TypeId__} header of {@link #CHARGES} records, in Spring Kafka's
     * {@code spring.json.type.mapping} format. Both sides use the same short names, so the producer's
     * class names never become part of the contract.
     */
    public static final String CHARGES_TYPE_MAPPING = "charge:io.github.burakboduroglu.ratekit.common.ChargeEvent,"
            + "watermark:io.github.burakboduroglu.ratekit.common.ChargeFeedWatermark";

    private Topics() {
    }
}
