package io.github.burakboduroglu.ratekit.rating.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {

    /** Decides what "now" is for new tariff versions; replaceable in tests. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
