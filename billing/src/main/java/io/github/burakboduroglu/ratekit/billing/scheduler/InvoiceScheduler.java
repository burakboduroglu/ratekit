package io.github.burakboduroglu.ratekit.billing.scheduler;

import io.github.burakboduroglu.ratekit.billing.exception.RatingNotCaughtUpException;
import io.github.burakboduroglu.ratekit.billing.exception.RatingProgressUnknownException;
import io.github.burakboduroglu.ratekit.common.BillingPeriod;
import io.github.burakboduroglu.ratekit.billing.service.InvoiceRunService;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Invoices the month that just ended on a schedule. Off unless {@code ratekit.billing.scheduler.enabled=true}.
 * It fires several times; a run that finds rating behind is skipped, and once the month is invoiced a
 * further run creates nothing.
 */
@Component
@ConditionalOnProperty(prefix = "ratekit.billing.scheduler", name = "enabled", havingValue = "true")
public class InvoiceScheduler {

    private static final Logger log = LoggerFactory.getLogger(InvoiceScheduler.class);

    private final InvoiceRunService runs;
    private final Clock clock;

    public InvoiceScheduler(InvoiceRunService runs, Clock clock) {
        this.runs = runs;
        this.clock = clock;
    }

    @Scheduled(cron = "${ratekit.billing.scheduler.cron}", zone = "UTC")
    void invoicePreviousMonth() {
        try {
            runs.run(BillingPeriod.containing(clock.instant()).previous());
        } catch (RatingNotCaughtUpException | RatingProgressUnknownException e) {
            log.warn("invoice run skipped, will try again: {}", e.getMessage());
        }
    }
}
