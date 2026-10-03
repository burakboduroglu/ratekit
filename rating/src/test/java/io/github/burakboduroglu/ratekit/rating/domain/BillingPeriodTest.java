package io.github.burakboduroglu.ratekit.rating.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class BillingPeriodTest {

    @Test
    void isTheCalendarMonthInUtc() {
        BillingPeriod p = BillingPeriod.containing(Instant.parse("2026-10-17T13:45:00Z"));

        assertThat(p.start()).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
        assertThat(p.end()).isEqualTo(Instant.parse("2026-11-01T00:00:00Z"));
    }

    @Test
    void monthEdgesBelongToTheRightMonth() {
        assertThat(BillingPeriod.containing(Instant.parse("2026-01-31T23:59:59.999Z")).start())
                .isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(BillingPeriod.containing(Instant.parse("2026-02-01T00:00:00Z")).start())
                .isEqualTo(Instant.parse("2026-02-01T00:00:00Z"));
    }

    @Test
    void decemberRollsIntoNextYear() {
        assertThat(BillingPeriod.containing(Instant.parse("2026-12-20T00:00:00Z")).end())
                .isEqualTo(Instant.parse("2027-01-01T00:00:00Z"));
    }

    @Test
    void februaryLengthFollowsTheCalendar() {
        assertThat(BillingPeriod.containing(Instant.parse("2028-02-29T12:00:00Z")).end())
                .isEqualTo(Instant.parse("2028-03-01T00:00:00Z"));
    }
}
