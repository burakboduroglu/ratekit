package io.github.burakboduroglu.ratekit.billing.repository;

import io.github.burakboduroglu.ratekit.common.BillingPeriod;
import io.github.burakboduroglu.ratekit.common.Money;

/** What an account used of one meter in one month, summed from the charges an invoice claimed. */
public record UsageRow(BillingPeriod period, String meter, long quantity, Money amount) {
}
