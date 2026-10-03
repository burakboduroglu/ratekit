package io.github.burakboduroglu.ratekit.billing.domain;

import io.github.burakboduroglu.ratekit.common.Money;
import java.util.Objects;

/** What one meter cost an account in a period. */
public record InvoiceLine(String meter, long quantity, Money amount) {

    public InvoiceLine {
        if (meter == null || meter.isBlank()) {
            throw new IllegalArgumentException("meter must not be blank");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive, got " + quantity);
        }
        Objects.requireNonNull(amount, "amount");
        if (amount.isNegative()) {
            throw new IllegalArgumentException("amount must not be negative, got " + amount);
        }
    }
}
