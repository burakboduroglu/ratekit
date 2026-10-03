package io.github.burakboduroglu.ratekit.rating.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How transient failures are retried before a record is dead-lettered. With the defaults a record
 * is tried once and retried after 0.5 s, 1 s, 2 s and 4 s (7.5 s at most) before it moves on.
 */
@ConfigurationProperties("ratekit.rating.retry")
public record RetryProperties(
        @DefaultValue("4") int maxRetries,
        @DefaultValue("500") long initialIntervalMs,
        @DefaultValue("2.0") double multiplier,
        @DefaultValue("5000") long maxIntervalMs) {
}
