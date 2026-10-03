package io.github.burakboduroglu.ratekit.billing.mapper;

import io.github.burakboduroglu.ratekit.common.BillingPeriod;
import io.github.burakboduroglu.ratekit.billing.exception.InvalidPeriodException;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import org.springframework.stereotype.Component;

/** Converts the text form of a period (2026-09) to {@link BillingPeriod} and back. */
@Component
public class BillingPeriodMapper {

    public BillingPeriod parse(String text) {
        try {
            return BillingPeriod.of(YearMonth.parse(text));
        } catch (DateTimeParseException | NullPointerException e) {
            throw new InvalidPeriodException(text);
        }
    }

    public String format(BillingPeriod period) {
        return period.month().toString();
    }
}
