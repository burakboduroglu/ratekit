package io.github.burakboduroglu.ratekit.billing.repository;

import io.github.burakboduroglu.ratekit.common.Money;

/** What one account used of one meter inside a period, summed from rating's charges. */
public record UsageRow(String accountId, String meter, long quantity, Money amount) {
}
