package io.github.burakboduroglu.ratekit.billing.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("ratekit.billing")
public record BillingProperties(@DefaultValue("500") int batchSize, @DefaultValue Scheduler scheduler,
                                @DefaultValue RatingProgress ratingProgress) {

    /** Hourly on the 1st from 02:00 UTC: a run that finds rating behind is skipped and the next hour tries again. */
    public record Scheduler(@DefaultValue("false") boolean enabled, @DefaultValue("0 0 2-23 1 * *") String cron) {
    }

    /**
     * The check that rating has rated everything a closed month can still receive (ADR 0010), and that
     * the resulting charges have reached billing's own table (ADR 0019).
     *
     * @param lateArrivalGrace must equal ingest's {@code ratekit.ingest.event-time.late-arrival-grace}:
     *                         ingest accepts usage for a month until this long after it ends
     * @param clockSkewMargin  added to billing's clock before it is compared with the charge feed's
     *                         markers, which carry rating's database clock
     * @param chargeFeedWait   how long an invoice run waits for a marker newer than its check
     */
    public record RatingProgress(@DefaultValue("true") boolean enabled,
                                 @DefaultValue("ratekit-rating") String consumerGroup,
                                 @DefaultValue("PT1H") Duration lateArrivalGrace,
                                 @DefaultValue("PT10S") Duration timeout,
                                 @DefaultValue("PT1S") Duration clockSkewMargin,
                                 @DefaultValue("PT30S") Duration chargeFeedWait) {
    }
}
