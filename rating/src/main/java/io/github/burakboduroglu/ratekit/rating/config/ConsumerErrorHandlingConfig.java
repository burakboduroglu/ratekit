package io.github.burakboduroglu.ratekit.rating.config;

import io.github.burakboduroglu.ratekit.rating.domain.NoTariffException;
import io.github.burakboduroglu.ratekit.rating.exception.UnknownAccountException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

/**
 * What the Kafka listener does when processing a record fails (Spring Boot applies this
 * {@code CommonErrorHandler} bean to the listener container automatically).
 *
 * <ul>
 *   <li>Transient failures (for example a database blip) are retried a bounded number of times
 *       with growing pauses.
 *   <li>Permanent failures never improve by waiting, so they go to the dead-letter topic at once:
 *       an unknown account, a meter with no tariff, and a message that cannot be deserialized
 *       (Spring Kafka treats that one as permanent by default).
 *   <li>After the last retry the record is dead-lettered and the partition moves on.
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(RetryProperties.class)
public class ConsumerErrorHandlingConfig {

    @Bean
    DefaultErrorHandler kafkaErrorHandler(DeadLetterPublishingRecoverer recoverer, RetryProperties retry) {
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(retry.maxRetries());
        backOff.setInitialInterval(retry.initialIntervalMs());
        backOff.setMultiplier(retry.multiplier());
        backOff.setMaxInterval(retry.maxIntervalMs());

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(NoTariffException.class, UnknownAccountException.class);
        return handler;
    }
}
