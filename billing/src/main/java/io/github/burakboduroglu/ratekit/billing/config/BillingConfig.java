package io.github.burakboduroglu.ratekit.billing.config;

import java.time.Clock;
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
}
