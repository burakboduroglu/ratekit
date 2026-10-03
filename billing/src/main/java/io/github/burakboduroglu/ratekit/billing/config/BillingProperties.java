package io.github.burakboduroglu.ratekit.billing.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("ratekit.billing")
public record BillingProperties(@DefaultValue("500") int batchSize, @DefaultValue Scheduler scheduler) {

    public record Scheduler(@DefaultValue("false") boolean enabled, @DefaultValue("0 0 2 1 * *") String cron) {
    }
}
