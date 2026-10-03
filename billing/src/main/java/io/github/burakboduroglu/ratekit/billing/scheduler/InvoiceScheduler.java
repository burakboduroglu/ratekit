package io.github.burakboduroglu.ratekit.billing.scheduler;

import io.github.burakboduroglu.ratekit.common.BillingPeriod;
import io.github.burakboduroglu.ratekit.billing.service.InvoiceRunService;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Invoices the month that just ended on a schedule. Off unless {@code ratekit.billing.scheduler.enabled=true}. */
@Component
@ConditionalOnProperty(prefix = "ratekit.billing.scheduler", name = "enabled", havingValue = "true")
public class InvoiceScheduler {

    private final InvoiceRunService runs;
    private final Clock clock;

    public InvoiceScheduler(InvoiceRunService runs, Clock clock) {
        this.runs = runs;
        this.clock = clock;
    }

    @Scheduled(cron = "${ratekit.billing.scheduler.cron}", zone = "UTC")
    void invoicePreviousMonth() {
        runs.run(BillingPeriod.containing(clock.instant()).previous());
    }
}
