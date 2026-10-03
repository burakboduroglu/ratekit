package io.github.burakboduroglu.ratekit.rating.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.burakboduroglu.ratekit.rating.domain.FlatPrice;
import io.github.burakboduroglu.ratekit.rating.domain.FreeQuotaThenFlat;
import io.github.burakboduroglu.ratekit.rating.domain.PriceModel;
import io.github.burakboduroglu.ratekit.rating.domain.Tariff;
import io.github.burakboduroglu.ratekit.rating.domain.TieredPrice;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TariffMapperTest {

    private final TariffMapper mapper = new TariffMapper(new ObjectMapper());

    @Test
    void buildsAFlatPrice() {
        PriceModel model = mapper.toModel("FLAT", "{\"rate\":\"0.05\"}");

        assertThat(model).isInstanceOf(FlatPrice.class);
        assertThat(model.cost(3)).isEqualByComparingTo("0.15");
    }

    @Test
    void keepsFullPrecisionOfASmallRateWhetherItIsAStringOrANumber() {
        assertThat(mapper.toModel("FLAT", "{\"rate\":\"0.00005\"}").cost(1)).isEqualByComparingTo("0.00005");
        assertThat(mapper.toModel("FLAT", "{\"rate\":0.00005}").cost(1)).isEqualByComparingTo("0.00005");
    }

    @Test
    void buildsAFreeQuotaModel() {
        PriceModel model = mapper.toModel("FREE_QUOTA_THEN_FLAT", "{\"freeUnits\":100,\"rate\":\"0.10\"}");

        assertThat(model).isInstanceOf(FreeQuotaThenFlat.class);
        assertThat(model.cost(100)).isEqualByComparingTo("0");
        assertThat(model.cost(101)).isEqualByComparingTo("0.10");
    }

    @Test
    void buildsTiersIncludingTheUnboundedLastOne() {
        PriceModel model = mapper.toModel("TIERED",
                "{\"tiers\":[{\"upTo\":100,\"rate\":\"0.10\"},{\"upTo\":null,\"rate\":\"0.05\"}]}");

        assertThat(model).isInstanceOf(TieredPrice.class);
        assertThat(model.cost(100)).isEqualByComparingTo("10.00");
        assertThat(model.cost(101)).isEqualByComparingTo("10.05");
    }

    @Test
    void rejectsAnUnknownModelName() {
        assertThatThrownBy(() -> mapper.toModel("MAGIC", "{}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MAGIC");
    }

    @Test
    void buildsAWholeTariffFromARow() {
        Instant from = Instant.parse("2026-01-01T00:00:00Z");

        Tariff tariff = mapper.toTariff(7, "sms", "FLAT", from, "{\"rate\":\"1\"}");

        assertThat(tariff.id()).isEqualTo(7);
        assertThat(tariff.meter()).isEqualTo("sms");
        assertThat(tariff.effectiveFrom()).isEqualTo(from);
        assertThat(tariff.model().cost(2)).isEqualByComparingTo(new BigDecimal("2"));
    }
}
