package io.github.burakboduroglu.ratekit.billing.exception;

import io.github.burakboduroglu.ratekit.common.BillingPeriod;

/** A period can only be invoiced after it has ended, otherwise the invoice would miss usage still to come. */
public class PeriodNotClosedException extends RuntimeException {

    public PeriodNotClosedException(BillingPeriod period) {
        super("period " + period.month() + " has not ended yet; it ends at " + period.end());
    }
}
