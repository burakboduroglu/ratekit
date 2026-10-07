package io.github.burakboduroglu.ratekit.ingest.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.burakboduroglu.ratekit.common.UsageEvent;
import io.github.burakboduroglu.ratekit.ingest.config.EventTimeProperties;
import io.github.burakboduroglu.ratekit.ingest.exception.EventTimeOutOfRangeException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class EventTimeWindowTest {

    private static final EventTimeProperties DEFAULTS = new EventTimeProperties(Duration.ofMinutes(5), Duration.ofHours(1));

    @Test
    void acceptsUsageFromEarlierThisMonthAndFromJustNow() {
        EventTimeWindow window = at("2026-10-07T12:00:00Z");

        assertThatCode(() -> window.check(event("2026-10-01T00:00:00Z"))).doesNotThrowAnyException();
        assertThatCode(() -> window.check(event("2026-10-07T12:00:00Z"))).doesNotThrowAnyException();
    }

    @Test
    void allowsAClientClockThatRunsUpToFiveMinutesFast() {
        EventTimeWindow window = at("2026-10-07T12:00:00Z");

        assertThatCode(() -> window.check(event("2026-10-07T12:05:00Z"))).doesNotThrowAnyException();
        assertThatThrownBy(() -> window.check(event("2026-10-07T12:05:01Z")))
                .isInstanceOf(EventTimeOutOfRangeException.class)
                .hasMessageContaining("future");
    }

    @Test
    void refusesUsageFromAMonthThatClosedLongerAgoThanTheGrace() {
        EventTimeWindow window = at("2026-10-07T12:00:00Z");

        assertThatThrownBy(() -> window.check(event("2026-09-30T23:59:59Z")))
                .isInstanceOf(EventTimeOutOfRangeException.class)
                .hasMessageContaining("closed billing month")
                .hasMessageContaining("2026-10-01T00:00:00Z");
    }

    @Test
    void acceptsLastMonthsUsageDuringTheGraceAfterTheMonthEnds() {
        // 00:59 on the 1st: September closed 59 minutes ago, inside the one-hour grace
        assertThatCode(() -> at("2026-10-01T00:59:59Z").check(event("2026-09-30T23:30:00Z"))).doesNotThrowAnyException();
        // 01:00 on the 1st: the grace is over, September now belongs to billing
        assertThatThrownBy(() -> at("2026-10-01T01:00:00Z").check(event("2026-09-30T23:30:00Z")))
                .isInstanceOf(EventTimeOutOfRangeException.class);
    }

    @Test
    void theMonthIsJudgedInUtcLikeBillingDoes() {
        // 01:00 on 1 October in Turkey is 22:00 on 30 September in UTC, so it is September usage
        EventTimeWindow window = at("2026-10-07T12:00:00Z");
        Instant turkeyFirstOfOctober = java.time.OffsetDateTime.parse("2026-10-01T01:00:00+03:00").toInstant();

        assertThatThrownBy(() -> window.check(new UsageEvent("e", "a", "sms", 1, turkeyFirstOfOctober)))
                .isInstanceOf(EventTimeOutOfRangeException.class);
    }

    private static EventTimeWindow at(String now) {
        return new EventTimeWindow(Clock.fixed(Instant.parse(now), ZoneOffset.UTC), DEFAULTS);
    }

    private static UsageEvent event(String occurredAt) {
        return new UsageEvent("e", "a", "sms", 1, Instant.parse(occurredAt));
    }
}
