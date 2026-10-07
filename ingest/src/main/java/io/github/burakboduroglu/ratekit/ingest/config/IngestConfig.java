package io.github.burakboduroglu.ratekit.ingest.config;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(EventTimeProperties.class)
public class IngestConfig {

    /** The clock the event time window is judged by; replaceable in tests. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
