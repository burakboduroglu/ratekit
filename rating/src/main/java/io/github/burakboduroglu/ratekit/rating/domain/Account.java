package io.github.burakboduroglu.ratekit.rating.domain;

import io.github.burakboduroglu.ratekit.common.Money;
import java.util.Objects;

/** A prepaid account and what it can still spend. */
public record Account(String id, Money balance) {

    public Account {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(balance, "balance");
    }
}
