package io.github.burakboduroglu.ratekit.billing.domain;

import io.github.burakboduroglu.ratekit.common.BillingPeriod;
import io.github.burakboduroglu.ratekit.common.Money;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * An account's bill for one period. The invariant: the total equals the sum of the lines. Lines are
 * sums of already-rounded charges, so the total is exact and never rounded again (ADR 0001).
 */
public record Invoice(String accountId, BillingPeriod period, List<InvoiceLine> lines, Money total) {

    public Invoice {
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalArgumentException("accountId must not be blank");
        }
        Objects.requireNonNull(period, "period");
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("an invoice needs at least one line");
        }
        lines = List.copyOf(lines);
        Objects.requireNonNull(total, "total");
        if (!sum(lines).equals(total)) {
            throw new IllegalArgumentException("total " + total + " does not equal the sum of the lines " + sum(lines));
        }
    }

    /** Builds an invoice from the lines, ordered by meter so the result is deterministic. */
    public static Invoice of(String accountId, BillingPeriod period, List<InvoiceLine> lines) {
        List<InvoiceLine> ordered = lines.stream().sorted(Comparator.comparing(InvoiceLine::meter)).toList();
        return new Invoice(accountId, period, ordered, sum(ordered));
    }

    private static Money sum(List<InvoiceLine> lines) {
        return lines.stream().map(InvoiceLine::amount).reduce(Money.ZERO, Money::plus);
    }
}
