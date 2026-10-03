package io.github.burakboduroglu.ratekit.rating.domain;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * The window in which usage accumulates toward free quotas and tiers: a calendar month in UTC.
 * Start is inclusive, end is exclusive.
 */
public record BillingPeriod(Instant start, Instant end) {

    public static BillingPeriod containing(Instant moment) {
        ZonedDateTime monthStart = moment.atZone(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1)
                .atStartOfDay(ZoneOffset.UTC);
        return new BillingPeriod(monthStart.toInstant(), monthStart.plusMonths(1).toInstant());
    }
}
