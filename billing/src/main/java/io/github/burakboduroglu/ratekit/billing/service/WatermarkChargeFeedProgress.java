package io.github.burakboduroglu.ratekit.billing.service;

import io.github.burakboduroglu.ratekit.billing.config.BillingProperties;
import io.github.burakboduroglu.ratekit.billing.repository.ChargeFeedWatermarkRepository;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Answers from the progress markers billing has stored (ADR 0019). rating's relay writes a marker on
 * every partition once everything committed before a moment is on the topic; billing stores a marker
 * only after the charges ahead of it on that partition. So once every partition's marker is at or
 * after a moment, every charge rating committed before it is in billing's table.
 *
 * <p>The markers carry rating's database clock and the moment asked about is billing's clock, so
 * {@code clock-skew-margin} is added to stay on the safe side. A marker newer than the moment can only
 * be written after it, so the check waits up to {@code charge-feed-wait} for one to arrive.
 */
@Service
@ConditionalOnProperty(prefix = "ratekit.billing.rating-progress", name = "enabled", havingValue = "true", matchIfMissing = true)
public class WatermarkChargeFeedProgress implements ChargeFeedProgress {

    private static final Logger log = LoggerFactory.getLogger(WatermarkChargeFeedProgress.class);
    private static final Duration POLL = Duration.ofMillis(250);

    private final ChargeFeedWatermarkRepository watermarks;
    private final Duration margin;
    private final Duration wait;

    @Autowired
    public WatermarkChargeFeedProgress(ChargeFeedWatermarkRepository watermarks, BillingProperties properties) {
        this(watermarks, properties.ratingProgress().clockSkewMargin(), properties.ratingProgress().chargeFeedWait());
    }

    WatermarkChargeFeedProgress(ChargeFeedWatermarkRepository watermarks, Duration margin, Duration wait) {
        this.watermarks = watermarks;
        this.margin = margin;
        this.wait = wait;
    }

    @Override
    public boolean deliveredUpTo(Instant ratedBy) {
        Instant required = ratedBy.plus(margin);
        long deadline = System.nanoTime() + wait.toNanos();
        while (!watermarks.allAtOrAfter(required)) {
            if (System.nanoTime() - deadline >= 0) {
                log.info("charges are still on their way to billing: no marker at or after {} on every partition", required);
                return false;
            }
            try {
                Thread.sleep(POLL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }
}
