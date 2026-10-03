package io.github.burakboduroglu.ratekit.billing.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.burakboduroglu.ratekit.common.BillingPeriod;
import io.github.burakboduroglu.ratekit.billing.exception.InvalidPeriodException;
import java.time.Instant;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;

class BillingPeriodMapperTest {

    private final BillingPeriodMapper mapper = new BillingPeriodMapper();

    @Test
    void parsesAYearAndMonthIntoAUtcCalendarMonth() {
        BillingPeriod period = mapper.parse("2026-09");

        assertThat(period.start()).isEqualTo(Instant.parse("2026-09-01T00:00:00Z"));
        assertThat(period.end()).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
    }

    @Test
    void formatsBackToTheSameText() {
        assertThat(mapper.format(BillingPeriod.of(YearMonth.of(2026, 9)))).isEqualTo("2026-09");
    }

    @Test
    void rejectsAnythingThatIsNotAYearAndMonth() {
        for (String bad : new String[] {"2026-13", "2026-9", "09-2026", "abc", "", "2026-09-01"}) {
            assertThatThrownBy(() -> mapper.parse(bad)).as(bad).isInstanceOf(InvalidPeriodException.class);
        }
        assertThatThrownBy(() -> mapper.parse(null)).isInstanceOf(InvalidPeriodException.class);
    }
}
