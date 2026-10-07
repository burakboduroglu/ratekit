package io.github.burakboduroglu.ratekit.rating.config;

import io.github.burakboduroglu.ratekit.rating.domain.NoTariffException;
import io.github.burakboduroglu.ratekit.rating.exception.InvalidTariffException;
import io.github.burakboduroglu.ratekit.rating.exception.UnknownAccountException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

/**
 * What the Kafka listener does when processing a record fails (Spring Boot applies this
 * {@code CommonErrorHandler} bean to the listener container automatically).
 *
 * <ul>
 *   <li>Transient failures (for example a database blip) are retried a bounded number of times
 *       with growing pauses.
 *   <li>Permanent failures never improve by waiting, so they go to the dead-letter topic at once:
 *       an unknown account, a meter with no tariff, a tariff row that is invalid, usage that
 *       overflows its counter, and a message that cannot be deserialized
 *       (Spring Kafka treats that one as permanent by default).
 *   <li>After the last retry the record is dead-lettered and the partition moves on.
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(RetryProperties.class)
public class ConsumerErrorHandlingConfig {

    @Bean
    DefaultErrorHandler kafkaErrorHandler(DeadLetterPublishingRecoverer recoverer, RetryProperties retry,
                                          MeterRegistry meters) {
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(retry.maxRetries());
        backOff.setInitialInterval(retry.initialIntervalMs());
        backOff.setMultiplier(retry.multiplier());
        backOff.setMaxInterval(retry.maxIntervalMs());

        // count every record that is given up on, by the root cause, then dead-letter it
        DefaultErrorHandler handler = new DefaultErrorHandler((record, exception) -> {
            Counter.builder("ratekit.events.dead_lettered")
                    .description("Records moved to the dead-letter topic, by root cause")
                    .tag("cause", NestedExceptionUtils.getMostSpecificCause(exception).getClass().getSimpleName())
                    .register(meters)
                    .increment();
            recoverer.accept(record, exception);
        }, backOff);
        // ArithmeticException: usage in the period overflowed a long, which no retry will undo
        handler.addNotRetryableExceptions(NoTariffException.class, UnknownAccountException.class,
                InvalidTariffException.class, ArithmeticException.class);
        return handler;
    }
}
