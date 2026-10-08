package io.github.burakboduroglu.ratekit.billing.domain;

import io.github.burakboduroglu.ratekit.common.BillingPeriod;
import io.github.burakboduroglu.ratekit.common.Money;
import java.util.Objects;

/**
 * Usage of an earlier, already invoiced month that reached billing after that month's run (a replayed
 * dead letter, for example), billed on a later invoice because an issued invoice is never rewritten
 * (ADR 0020).
 */
public record AdjustmentLine(BillingPeriod originalPeriod, String meter, long quantity, Money amount) {

    public AdjustmentLine {
        Objects.requireNonNull(originalPeriod, "originalPeriod");
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
