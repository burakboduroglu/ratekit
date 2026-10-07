package io.github.burakboduroglu.ratekit.rating.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(TariffCacheProperties.class)
public class TariffCacheConfig {
}
