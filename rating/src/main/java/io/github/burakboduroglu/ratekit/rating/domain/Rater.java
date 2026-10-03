package io.github.burakboduroglu.ratekit.rating.domain;

import io.github.burakboduroglu.ratekit.common.Money;
import io.github.burakboduroglu.ratekit.common.UsageEvent;
import java.math.BigDecimal;

/** Prices one event. Pure logic: no I/O, no framework. */
public final class Rater {

    private final TariffBook book;

    public Rater(TariffBook book) {
        this.book = book;
    }

    /**
     * @param usedBefore units of this meter the account already consumed earlier in the same period
     * @throws NoTariffException if no tariff version applied when the usage happened
     */
    public Charge rate(UsageEvent event, long usedBefore) {
        Tariff tariff = book.at(event.meter(), event.occurredAt())
                .orElseThrow(() -> new NoTariffException(event.meter(), event.occurredAt()));
        long usedAfter = Math.addExact(usedBefore, event.quantity());
        BigDecimal exact = tariff.model().cost(usedAfter).subtract(tariff.model().cost(usedBefore));
        return new Charge(tariff.id(), new Money(exact));
    }
}
