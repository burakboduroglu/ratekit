package io.github.burakboduroglu.ratekit.ingest.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Which usage times ingest accepts (ADR 0007).
 *
 * @param maxFutureSkew      how far ahead of this server's clock an event may be, to allow for clocks
 *                           that run slightly fast
 * @param lateArrivalGrace   how long after a month ends usage for that month is still accepted; must
 *                           end before billing invoices the month (02:00 UTC on the 1st by default)
 */
@ConfigurationProperties("ratekit.ingest.event-time")
public record EventTimeProperties(
        @DefaultValue("PT5M") Duration maxFutureSkew,
        @DefaultValue("PT1H") Duration lateArrivalGrace) {
}
