package io.github.burakboduroglu.ratekit.rating.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class PriceModelsTest {

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    @Test
    void flatPricesEveryUnitTheSame() {
        FlatPrice flat = new FlatPrice(bd("0.05"));

        assertThat(flat.cost(0)).isEqualByComparingTo("0");
        assertThat(flat.cost(1)).isEqualByComparingTo("0.05");
        assertThat(flat.cost(40)).isEqualByComparingTo("2.00");
    }

    @Test
    void freeQuotaCostsNothingUpToTheQuotaThenChargesEveryUnit() {
        FreeQuotaThenFlat model = new FreeQuotaThenFlat(100, bd("0.10"));

        assertThat(model.cost(99)).isEqualByComparingTo("0");
        assertThat(model.cost(100)).isEqualByComparingTo("0");
        assertThat(model.cost(101)).isEqualByComparingTo("0.10");
        assertThat(model.cost(150)).isEqualByComparingTo("5.00");
    }

    @Test
    void tiersAreGraduatedAtTheirBoundaries() {
        TieredPrice tiered = new TieredPrice(List.of(
                new TieredPrice.Tier(100L, bd("0.10")),
                new TieredPrice.Tier(1000L, bd("0.05")),
                new TieredPrice.Tier(null, bd("0.01"))));

        assertThat(tiered.cost(0)).isEqualByComparingTo("0");
        assertThat(tiered.cost(100)).isEqualByComparingTo("10.00");          // last unit of tier 1
        assertThat(tiered.cost(101)).isEqualByComparingTo("10.05");          // first unit of tier 2
        assertThat(tiered.cost(1000)).isEqualByComparingTo("55.00");         // 10 + 900 * 0.05
        assertThat(tiered.cost(1001)).isEqualByComparingTo("55.01");         // first unit of tier 3
        assertThat(tiered.cost(2000)).isEqualByComparingTo("65.00");         // 55 + 1000 * 0.01
    }

    @Test
    void singleUnboundedTierBehavesLikeFlat() {
        TieredPrice tiered = new TieredPrice(List.of(new TieredPrice.Tier(null, bd("0.02"))));

        assertThat(tiered.cost(500)).isEqualByComparingTo("10.00");
    }

    @Test
    void tiersMustBeWellFormed() {
        assertThatThrownBy(() -> new TieredPrice(List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TieredPrice(List.of(new TieredPrice.Tier(100L, bd("1")))))
                .hasMessageContaining("last tier must be unbounded");
        assertThatThrownBy(() -> new TieredPrice(List.of(
                new TieredPrice.Tier(null, bd("1")), new TieredPrice.Tier(null, bd("1")))))
                .hasMessageContaining("only the last tier");
        assertThatThrownBy(() -> new TieredPrice(List.of(
                new TieredPrice.Tier(100L, bd("1")), new TieredPrice.Tier(100L, bd("1")),
                new TieredPrice.Tier(null, bd("1")))))
                .hasMessageContaining("ascending");
        assertThatThrownBy(() -> new TieredPrice.Tier(0L, bd("1"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNegativeRatesAndNegativeUnits() {
        assertThatThrownBy(() -> new FlatPrice(bd("-0.01"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FreeQuotaThenFlat(-1, bd("1"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FlatPrice(bd("1")).cost(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
