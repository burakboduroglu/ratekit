package io.github.burakboduroglu.ratekit.rating.scheduler;

import io.github.burakboduroglu.ratekit.rating.service.RetentionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs the retention cleanup daily. On by default; {@code ratekit.rating.retention.enabled=false} turns it off. */
@Component
@ConditionalOnProperty(prefix = "ratekit.rating.retention", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RetentionScheduler {

    private final RetentionService retention;

    public RetentionScheduler(RetentionService retention) {
        this.retention = retention;
    }

    @Scheduled(cron = "${ratekit.rating.retention.cron:0 30 3 * * *}", zone = "UTC")
    void prune() {
        retention.pruneRejected();
    }
}
