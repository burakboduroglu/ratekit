package io.github.burakboduroglu.ratekit.rating.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.burakboduroglu.ratekit.rating.domain.FlatPrice;
import io.github.burakboduroglu.ratekit.rating.domain.FreeQuotaThenFlat;
import io.github.burakboduroglu.ratekit.rating.domain.PriceModel;
import io.github.burakboduroglu.ratekit.rating.domain.Tariff;
import io.github.burakboduroglu.ratekit.rating.domain.TieredPrice;
import io.github.burakboduroglu.ratekit.rating.exception.InvalidTariffException;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",                                                  // rate missing
            "{\"rate\":\"-1\"}",                                   // negative rate
            "{\"rate\":\"abc\"}",                                  // not a number
            "not json"})
    void reportsAnInvalidRowAsOneExceptionTypeNamingTheTariff(String params) {
        assertThatThrownBy(() -> mapper.toTariff(7, "sms", "FLAT", Instant.EPOCH, params))
                .isInstanceOf(InvalidTariffException.class)
                .hasMessageContaining("tariff 7")
                .hasMessageContaining("sms")
                .hasCauseInstanceOf(RuntimeException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"freeUnits\":\"abc\",\"rate\":\"0.05\"}",   // text, used to become 0
            "{\"freeUnits\":1.5,\"rate\":\"0.05\"}",         // fraction, used to become 1
            "{\"rate\":\"0.05\"}"})                             // missing
    void refusesAFreeQuotaThatIsNotAWholeNumberInsteadOfGuessing(String params) {
        assertThatThrownBy(() -> mapper.toModel("FREE_QUOTA_THEN_FLAT", params))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("freeUnits must be a whole number");
    }

    @Test
    void refusesATierBoundGivenAsText() {
        assertThatThrownBy(() -> mapper.toModel("TIERED",
                "{\"tiers\":[{\"upTo\":\"100\",\"rate\":\"0.10\"},{\"upTo\":null,\"rate\":\"0.05\"}]}"))
                .hasMessageContaining("upTo must be a whole number");
    }

    @Test
    void reportsInvalidTiersAsAnInvalidTariff() {
        String descending = "{\"tiers\":[{\"upTo\":100,\"rate\":\"0.10\"},{\"upTo\":50,\"rate\":\"0.05\"},"
                + "{\"upTo\":null,\"rate\":\"0.01\"}]}";

        assertThatThrownBy(() -> mapper.toTariff(8, "data-mb", "TIERED", Instant.EPOCH, descending))
                .isInstanceOf(InvalidTariffException.class)
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
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
