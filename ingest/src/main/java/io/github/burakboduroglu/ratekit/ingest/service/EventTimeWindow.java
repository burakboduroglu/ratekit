package io.github.burakboduroglu.ratekit.ingest.service;

import io.github.burakboduroglu.ratekit.common.BillingPeriod;
import io.github.burakboduroglu.ratekit.common.UsageEvent;
import io.github.burakboduroglu.ratekit.ingest.config.EventTimeProperties;
import io.github.burakboduroglu.ratekit.ingest.exception.EventTimeOutOfRangeException;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Decides whether an event's usage time can still be billed (ADR 0007).
 *
 * <ul>
 *   <li>Latest: now plus a small allowance for clocks that run fast. Later than that is a client bug,
 *       and it would count toward a month that has not started.
 *   <li>Earliest: the start of the month that was current {@code lateArrivalGrace} ago. Right after a
 *       month ends, its usage is still accepted for that grace; after it, the month belongs to billing
 *       and an event for it would be charged to the balance but never appear on an invoice.
 * </ul>
 */
@Component
public class EventTimeWindow {

    private final Clock clock;
    private final EventTimeProperties properties;

    public EventTimeWindow(Clock clock, EventTimeProperties properties) {
        this.clock = clock;
        this.properties = properties;
    }

    /** @throws EventTimeOutOfRangeException if the event cannot be accepted at this moment */
    public void check(UsageEvent event) {
        Instant now = clock.instant();
        Instant latest = now.plus(properties.maxFutureSkew());
        Instant earliest = BillingPeriod.containing(now.minus(properties.lateArrivalGrace())).start();
        if (event.occurredAt().isAfter(latest)) {
            throw new EventTimeOutOfRangeException(
                    "occurredAt " + event.occurredAt() + " is in the future; the latest accepted is " + latest);
        }
        if (event.occurredAt().isBefore(earliest)) {
            throw new EventTimeOutOfRangeException(
                    "occurredAt " + event.occurredAt() + " is in a closed billing month; the earliest accepted is " + earliest);
        }
    }
}
