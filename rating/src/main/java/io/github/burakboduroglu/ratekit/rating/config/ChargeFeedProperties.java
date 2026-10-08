package io.github.burakboduroglu.ratekit.rating.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How the outbox relay sends charges to billing (ADR 0019).
 *
 * @param enabled            off only for tests and tools that run rating without Kafka
 * @param batchSize          outbox rows sent per database transaction
 * @param pollInterval       pause between two relay passes
 * @param watermarkInterval  least time between two progress markers; billing waits for one before it
 *                           invoices, so this is roughly how long an invoice run waits
 * @param sendTimeout        how long a batch waits for Kafka's acknowledgements before it is rolled
 *                           back and sent again on the next pass
 */
@ConfigurationProperties("ratekit.rating.charge-feed")
public record ChargeFeedProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("500") int batchSize,
        @DefaultValue("PT0.5S") Duration pollInterval,
        @DefaultValue("PT2S") Duration watermarkInterval,
        @DefaultValue("PT10S") Duration sendTimeout) {

    public ChargeFeedProperties {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("ratekit.rating.charge-feed.batch-size must be positive, got " + batchSize);
        }
    }
}
