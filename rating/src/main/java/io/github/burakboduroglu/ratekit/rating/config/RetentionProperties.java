package io.github.burakboduroglu.ratekit.rating.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How long refused events are kept, and how the cleanup runs (ADR 0014).
 *
 * @param rejectedMaxAge a rejected event, and its idempotency row, is deleted this long after it was
 *                       rejected. It must outlive every way the same event could come back: Kafka
 *                       redelivery and replay (topic retention, 7 days by default) and a client
 *                       retry through ingest (accepted until about a month after the usage, ADR 0007).
 *                       If it came back after deletion it would be rated again, and charged if the
 *                       account has money by then.
 * @param batchSize      rows deleted per statement, so a large backlog never holds locks for long
 */
@ConfigurationProperties("ratekit.rating.retention")
public record RetentionProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("P90D") Duration rejectedMaxAge,
        @DefaultValue("1000") int batchSize,
        @DefaultValue("0 30 3 * * *") String cron) {

    /** Longest month plus ingest's grace plus a margin: anything shorter could re-rate a retried event. */
    public static final Duration SHORTEST_SAFE_AGE = Duration.ofDays(35);

    public RetentionProperties {
        if (rejectedMaxAge.compareTo(SHORTEST_SAFE_AGE) < 0) {
            throw new IllegalArgumentException("ratekit.rating.retention.rejected-max-age must be at least "
                    + SHORTEST_SAFE_AGE + ", got " + rejectedMaxAge);
        }
        if (batchSize <= 0) {
            throw new IllegalArgumentException("ratekit.rating.retention.batch-size must be positive, got " + batchSize);
        }
    }
}
