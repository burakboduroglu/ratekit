package io.github.burakboduroglu.ratekit.billing.config;

import io.github.burakboduroglu.ratekit.billing.service.RatingProgress;
import java.time.Clock;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(BillingProperties.class)
public class BillingConfig {

    /** The clock the invoice run uses to decide whether a period has ended; replaceable in tests. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** For running billing without Kafka (a debugger, a demo). Invoices may then miss usage still in flight. */
    @Bean
    @ConditionalOnProperty(prefix = "ratekit.billing.rating-progress", name = "enabled", havingValue = "false")
    RatingProgress ratingProgressNotChecked() {
        LoggerFactory.getLogger(BillingConfig.class).warn("rating progress check is off: invoice runs do not wait for rating");
        return cutoff -> true;
    }
}
