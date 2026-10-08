package io.github.burakboduroglu.ratekit.billing.domain;

import io.github.burakboduroglu.ratekit.common.BillingPeriod;
import io.github.burakboduroglu.ratekit.common.Money;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * An account's bill for one period: a line per meter used in the period, and an adjustment line per
 * earlier month and meter for usage that arrived after that month was invoiced (ADR 0020). The
 * invariant: the total equals the sum of all lines. Lines are sums of already-rounded charges, so the
 * total is exact and never rounded again (ADR 0001).
 */
public record Invoice(String accountId, BillingPeriod period, List<InvoiceLine> lines,
                      List<AdjustmentLine> adjustments, Money total) {

    public Invoice {
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalArgumentException("accountId must not be blank");
        }
        Objects.requireNonNull(period, "period");
        lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
        adjustments = List.copyOf(Objects.requireNonNull(adjustments, "adjustments"));
        if (lines.isEmpty() && adjustments.isEmpty()) {
            throw new IllegalArgumentException("an invoice needs at least one line or adjustment");
        }
        for (AdjustmentLine adjustment : adjustments) {
            if (!adjustment.originalPeriod().start().isBefore(period.start())) {
                throw new IllegalArgumentException("an adjustment must be for a month before " + period.month()
                        + ", got " + adjustment.originalPeriod().month());
            }
        }
        Objects.requireNonNull(total, "total");
        if (!sum(lines, adjustments).equals(total)) {
            throw new IllegalArgumentException("total " + total + " does not equal the sum of the lines "
                    + sum(lines, adjustments));
        }
    }

    /** An invoice without adjustments. */
    public static Invoice of(String accountId, BillingPeriod period, List<InvoiceLine> lines) {
        return of(accountId, period, lines, List.of());
    }

    /** Orders lines by meter and adjustments by month, then meter, so the result is deterministic. */
    public static Invoice of(String accountId, BillingPeriod period, List<InvoiceLine> lines,
                             List<AdjustmentLine> adjustments) {
        List<InvoiceLine> orderedLines = lines.stream().sorted(Comparator.comparing(InvoiceLine::meter)).toList();
        List<AdjustmentLine> orderedAdjustments = adjustments.stream()
                .sorted(Comparator.comparing((AdjustmentLine a) -> a.originalPeriod().start())
                        .thenComparing(AdjustmentLine::meter))
                .toList();
        return new Invoice(accountId, period, orderedLines, orderedAdjustments, sum(orderedLines, orderedAdjustments));
    }

    private static Money sum(List<InvoiceLine> lines, List<AdjustmentLine> adjustments) {
        Money linesTotal = lines.stream().map(InvoiceLine::amount).reduce(Money.ZERO, Money::plus);
        return adjustments.stream().map(AdjustmentLine::amount).reduce(linesTotal, Money::plus);
    }
}
