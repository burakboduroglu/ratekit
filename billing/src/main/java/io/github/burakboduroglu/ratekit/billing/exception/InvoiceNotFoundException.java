package io.github.burakboduroglu.ratekit.billing.exception;

import io.github.burakboduroglu.ratekit.common.BillingPeriod;

public class InvoiceNotFoundException extends RuntimeException {

    public InvoiceNotFoundException(String accountId, BillingPeriod period) {
        super("no invoice for account '" + accountId + "' in " + period.month());
    }
}
