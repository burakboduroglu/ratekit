package io.github.burakboduroglu.ratekit.common;

import java.time.Instant;
import java.util.Objects;

/**
 * One metered usage occurrence, the unit of work that flows ingest to rating.
 *
 * @param eventId    unique per event within an account; the idempotency key
 * @param accountId  who is charged; also the Kafka message key
 * @param meter      what was used, for example "sms" or "data-mb"
 * @param quantity   amount used, in the meter's smallest whole unit; always positive
 * @param occurredAt when the usage happened (not when it was received); picks the tariff version
 */
public record UsageEvent(String eventId, String accountId, String meter, long quantity, Instant occurredAt) {

    public UsageEvent {
        requireText(eventId, "eventId");
        requireText(accountId, "accountId");
        requireText(meter, "meter");
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive, got " + quantity);
        }
        Objects.requireNonNull(occurredAt, "occurredAt");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
