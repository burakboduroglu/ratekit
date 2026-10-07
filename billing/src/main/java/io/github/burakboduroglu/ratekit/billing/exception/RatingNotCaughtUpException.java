package io.github.burakboduroglu.ratekit.billing.exception;

import io.github.burakboduroglu.ratekit.common.BillingPeriod;
import java.time.Instant;

/** Usage for the period may still be on its way to a charge; invoicing now could leave it out. */
public class RatingNotCaughtUpException extends RuntimeException {

    public RatingNotCaughtUpException(BillingPeriod period, Instant cutoff) {
        super("rating has not yet rated all usage accepted for " + period.month() + " (everything written before "
                + cutoff + "); try again later");
    }
}
