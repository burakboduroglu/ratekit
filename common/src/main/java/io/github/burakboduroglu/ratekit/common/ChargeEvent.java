package io.github.burakboduroglu.ratekit.common;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * One charge rating stored, as it travels to billing on the {@code charges} topic (ADR 0019).
 *
 * <p>The amount is already rounded by rating (ADR 0001), so it never has more than
 * {@value Money#SCALE} decimals; billing sums it without rounding again.
 *
 * @param eventId    the usage event that produced the charge; with the account, the idempotency key
 * @param accountId  who was charged; also the Kafka message key, so one account's charges stay in order
 * @param meter      what was used
 * @param quantity   units of the event, always positive
 * @param amount     what the event cost, never negative
 * @param occurredAt when the usage happened; picks the invoice month
 * @param ratedAt    when rating stored the charge (its database transaction)
 */
public record ChargeEvent(String eventId, String accountId, String meter, long quantity, BigDecimal amount,
                          Instant occurredAt, Instant ratedAt) {

    public ChargeEvent {
        requireText(eventId, "eventId");
        requireText(accountId, "accountId");
        requireText(meter, "meter");
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive, got " + quantity);
        }
        Objects.requireNonNull(amount, "amount");
        if (amount.signum() < 0 || amount.stripTrailingZeros().scale() > Money.SCALE) {
            throw new IllegalArgumentException("amount must be a non-negative amount with at most "
                    + Money.SCALE + " decimals, got " + amount);
        }
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(ratedAt, "ratedAt");
    }

    /** The amount as {@link Money}; exact, because it already has at most four decimals. */
    public Money money() {
        return new Money(amount);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
