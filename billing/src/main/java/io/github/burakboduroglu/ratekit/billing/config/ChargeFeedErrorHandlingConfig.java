package io.github.burakboduroglu.ratekit.billing.config;

import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * What billing's charge-feed listener does when storing a record fails (Spring Boot applies this
 * {@code CommonErrorHandler} bean to the listener container automatically).
 *
 * <p>A charge is never skipped: Spring Kafka's default would log and move on after ten attempts, and
 * the next progress marker would then claim a charge billing does not have, so an invoice would miss
 * it. Instead every failure, a record that cannot be read included, is retried with growing pauses up
 * to a minute apart, for as long as it takes. The partition stops, its markers stop with it, and
 * invoice runs are refused (409) until the cause is fixed: loud, not lossy.
 */
@Configuration
public class ChargeFeedErrorHandlingConfig {

    @Bean
    DefaultErrorHandler kafkaErrorHandler() {
        ExponentialBackOff backOff = new ExponentialBackOff(500, 2.0);
        backOff.setMaxInterval(60_000); // and no maximum elapsed time: retried until it succeeds
        DefaultErrorHandler handler = new DefaultErrorHandler((record, exception) -> {
            throw new IllegalStateException("unreachable: the back-off never ends", exception);
        }, backOff);
        // by default deserialization and conversion errors are not retried; here nothing is given up
        handler.setClassifications(Map.of(), true);
        return handler;
    }
}
