package io.github.burakboduroglu.ratekit.billing.exception;

import io.github.burakboduroglu.ratekit.common.BillingPeriod;
import java.time.Instant;

/** rating has rated the period, but some of its charges have not reached billing's table yet. */
public class ChargesInFlightException extends RuntimeException {

    public ChargesInFlightException(BillingPeriod period, Instant ratedBy) {
        super("charges for " + period.month() + " rated before " + ratedBy
                + " have not all reached billing yet; try again later");
    }
}
