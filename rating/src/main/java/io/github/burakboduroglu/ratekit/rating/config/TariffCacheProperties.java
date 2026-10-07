package io.github.burakboduroglu.ratekit.rating.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How long rating keeps a meter's tariff versions in memory (ADR 0012). It is also the longest another
 * rating instance may keep pricing with the versions it had before a new one was added, so a new
 * version may start no earlier than now plus this; {@code PT0S} turns the cache off.
 */
@ConfigurationProperties("ratekit.rating.tariff-cache")
public record TariffCacheProperties(@DefaultValue("PT30S") Duration ttl) {
}
