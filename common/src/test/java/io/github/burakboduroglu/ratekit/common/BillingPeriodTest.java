package io.github.burakboduroglu.ratekit.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;

class BillingPeriodTest {

    @Test
    void isTheCalendarMonthInUtc() {
        BillingPeriod p = BillingPeriod.containing(Instant.parse("2026-10-17T13:45:00Z"));

        assertEquals(Instant.parse("2026-10-01T00:00:00Z"), p.start());
        assertEquals(Instant.parse("2026-11-01T00:00:00Z"), p.end());
    }

    @Test
    void monthEdgesBelongToTheRightMonth() {
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"),
                BillingPeriod.containing(Instant.parse("2026-01-31T23:59:59.999Z")).start());
        assertEquals(Instant.parse("2026-02-01T00:00:00Z"),
                BillingPeriod.containing(Instant.parse("2026-02-01T00:00:00Z")).start());
    }

    @Test
    void anInstantIsJudgedInUtcNotInLocalTime() {
        // 01:00 on 1 October in Turkey (UTC+3) is still 22:00 on 30 September in UTC
        Instant localOctoberFirst = java.time.OffsetDateTime.parse("2026-10-01T01:00:00+03:00").toInstant();

        assertEquals(YearMonth.of(2026, 9), BillingPeriod.containing(localOctoberFirst).month());
    }

    @Test
    void decemberRollsIntoNextYear() {
        assertEquals(Instant.parse("2027-01-01T00:00:00Z"),
                BillingPeriod.containing(Instant.parse("2026-12-20T00:00:00Z")).end());
    }

    @Test
    void februaryLengthFollowsTheCalendar() {
        assertEquals(Instant.parse("2028-03-01T00:00:00Z"),
                BillingPeriod.containing(Instant.parse("2028-02-29T12:00:00Z")).end());
    }

    @Test
    void ofAMonthMatchesContaining() {
        assertEquals(BillingPeriod.containing(Instant.parse("2026-09-15T00:00:00Z")), BillingPeriod.of(YearMonth.of(2026, 9)));
    }

    @Test
    void previousStepsBackOneMonthAcrossAYearBoundary() {
        BillingPeriod january = BillingPeriod.of(YearMonth.of(2027, 1));

        assertEquals(YearMonth.of(2026, 12), january.previous().month());
        assertEquals(january.start(), january.previous().end());
    }
}
