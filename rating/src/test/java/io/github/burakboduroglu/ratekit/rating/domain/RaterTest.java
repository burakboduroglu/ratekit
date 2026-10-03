package io.github.burakboduroglu.ratekit.rating.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.burakboduroglu.ratekit.common.Money;
import io.github.burakboduroglu.ratekit.common.UsageEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class RaterTest {

    private static final Instant JAN = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant JUL = Instant.parse("2026-07-01T00:00:00Z");

    private static UsageEvent event(String meter, long quantity, Instant at) {
        return new UsageEvent("e1", "acc-1", meter, quantity, at);
    }

    @Test
    void flatChargeIsQuantityTimesRate() {
        Rater rater = new Rater(new TariffBook(List.of(
                new Tariff(7, "sms", JAN, new FlatPrice(new BigDecimal("0.05"))))));

        Charge charge = rater.rate(event("sms", 3, JAN.plusSeconds(60)), 0);

        assertThat(charge.amount()).isEqualTo(Money.of("0.15"));
        assertThat(charge.tariffId()).isEqualTo(7);
    }

    @Test
    void anEventThatCrossesTheFreeQuotaOnlyPaysForTheExcess() {
        Rater rater = new Rater(new TariffBook(List.of(
                new Tariff(1, "sms", JAN, new FreeQuotaThenFlat(100, new BigDecimal("0.10"))))));

        // 95 already used, 10 more: 5 are still free, 5 are paid
        Charge charge = rater.rate(event("sms", 10, JAN.plusSeconds(1)), 95);

        assertThat(charge.amount()).isEqualTo(Money.of("0.50"));
    }

    @Test
    void anEventInsideTheFreeQuotaIsFree() {
        Rater rater = new Rater(new TariffBook(List.of(
                new Tariff(1, "sms", JAN, new FreeQuotaThenFlat(100, new BigDecimal("0.10"))))));

        assertThat(rater.rate(event("sms", 10, JAN.plusSeconds(1)), 0).amount()).isEqualTo(Money.ZERO);
    }

    @Test
    void anEventThatCrossesATierBoundaryIsSplitAcrossTiers() {
        TieredPrice tiers = new TieredPrice(List.of(
                new TieredPrice.Tier(100L, new BigDecimal("0.10")),
                new TieredPrice.Tier(null, new BigDecimal("0.05"))));
        Rater rater = new Rater(new TariffBook(List.of(new Tariff(1, "data-mb", JAN, tiers))));

        // 90 used, 20 more: 10 at 0.10 + 10 at 0.05 = 1.50
        assertThat(rater.rate(event("data-mb", 20, JAN.plusSeconds(1)), 90).amount()).isEqualTo(Money.of("1.50"));
    }

    @Test
    void usesTheTariffVersionOfTheEventTimeNotOfNow() {
        Rater rater = new Rater(new TariffBook(List.of(
                new Tariff(1, "sms", JAN, new FlatPrice(new BigDecimal("0.05"))),
                new Tariff(2, "sms", JUL, new FlatPrice(new BigDecimal("0.08"))))));

        Charge before = rater.rate(event("sms", 10, JUL.minusSeconds(1)), 0);
        Charge after = rater.rate(event("sms", 10, JUL), 0);

        assertThat(before.tariffId()).isEqualTo(1);
        assertThat(before.amount()).isEqualTo(Money.of("0.50"));
        assertThat(after.tariffId()).isEqualTo(2);
        assertThat(after.amount()).isEqualTo(Money.of("0.80"));
    }

    @Test
    void roundsOnceAtTheEndWithHalfEven() {
        // rate has more precision than Money: 3 * 0.00005 = 0.00015, half-even gives 0.0002
        Rater rater = new Rater(new TariffBook(List.of(
                new Tariff(1, "kb", JAN, new FlatPrice(new BigDecimal("0.00005"))))));

        assertThat(rater.rate(event("kb", 3, JAN.plusSeconds(1)), 0).amount()).isEqualTo(Money.of("0.0002"));
        // 1 * 0.00005 is exactly half: even neighbour is 0.0000
        assertThat(rater.rate(event("kb", 1, JAN.plusSeconds(1)), 0).amount()).isEqualTo(Money.of("0.0000"));
    }

    @Test
    void failsClearlyWhenNoTariffApplies() {
        Rater rater = new Rater(new TariffBook(List.of(
                new Tariff(1, "sms", JUL, new FlatPrice(BigDecimal.ONE)))));

        assertThatThrownBy(() -> rater.rate(event("sms", 1, JUL.minusSeconds(1)), 0))
                .isInstanceOf(NoTariffException.class);
        assertThatThrownBy(() -> rater.rate(event("voice", 1, JUL), 0))
                .isInstanceOf(NoTariffException.class);
    }
}
