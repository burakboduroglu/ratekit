package io.github.burakboduroglu.ratekit.common;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * The window in which usage accumulates toward free quotas and tiers, and which an invoice covers:
 * a calendar month in UTC. Start is inclusive, end is exclusive. Rating and billing must agree on
 * this definition, which is why it lives in {@code common}.
 */
public record BillingPeriod(Instant start, Instant end) {

    public static BillingPeriod containing(Instant moment) {
        return of(YearMonth.from(moment.atZone(ZoneOffset.UTC)));
    }

    public static BillingPeriod of(YearMonth month) {
        ZonedDateTime monthStart = month.atDay(1).atStartOfDay(ZoneOffset.UTC);
        return new BillingPeriod(monthStart.toInstant(), monthStart.plusMonths(1).toInstant());
    }

    /** The calendar month before this one. */
    public BillingPeriod previous() {
        return containing(start.minusNanos(1));
    }

    public YearMonth month() {
        return YearMonth.from(start.atZone(ZoneOffset.UTC));
    }
}
