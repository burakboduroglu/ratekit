package io.github.burakboduroglu.ratekit.rating.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.burakboduroglu.ratekit.rating.domain.FlatPrice;
import io.github.burakboduroglu.ratekit.rating.domain.FreeQuotaThenFlat;
import io.github.burakboduroglu.ratekit.rating.domain.PriceModel;
import io.github.burakboduroglu.ratekit.rating.domain.Tariff;
import io.github.burakboduroglu.ratekit.rating.domain.TieredPrice;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Turns a stored tariff row (model name plus JSON parameters) into the domain {@link Tariff}.
 * Keeps JSON knowledge out of the repository and out of the domain.
 *
 * <p>Stored shapes:
 * FLAT {"rate":"0.05"}; FREE_QUOTA_THEN_FLAT {"freeUnits":100,"rate":"0.10"};
 * TIERED {"tiers":[{"upTo":100,"rate":"0.10"},{"upTo":null,"rate":"0.05"}]}.
 */
@Component
public class TariffMapper {

    private final ObjectMapper json;

    public TariffMapper(ObjectMapper json) {
        this.json = json;
    }

    public Tariff toTariff(long id, String meter, String model, Instant effectiveFrom, String paramsJson) {
        return new Tariff(id, meter, effectiveFrom, toModel(model, paramsJson));
    }

    public PriceModel toModel(String name, String paramsJson) {
        JsonNode params = read(paramsJson);
        return switch (name) {
            case "FLAT" -> new FlatPrice(rate(params));
            case "FREE_QUOTA_THEN_FLAT" -> new FreeQuotaThenFlat(params.get("freeUnits").asLong(), rate(params));
            case "TIERED" -> new TieredPrice(tiers(params.get("tiers")));
            default -> throw new IllegalStateException("unknown price model " + name);
        };
    }

    private static List<TieredPrice.Tier> tiers(JsonNode tiers) {
        List<TieredPrice.Tier> result = new ArrayList<>();
        for (JsonNode tier : tiers) {
            JsonNode upTo = tier.get("upTo");
            result.add(new TieredPrice.Tier(upTo == null || upTo.isNull() ? null : upTo.asLong(), rate(tier)));
        }
        return result;
    }

    private static BigDecimal rate(JsonNode node) {
        return new BigDecimal(node.get("rate").asText());
    }

    private JsonNode read(String paramsJson) {
        try {
            return json.readTree(paramsJson);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
