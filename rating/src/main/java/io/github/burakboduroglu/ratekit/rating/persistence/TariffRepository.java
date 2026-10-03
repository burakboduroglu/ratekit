package io.github.burakboduroglu.ratekit.rating.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.burakboduroglu.ratekit.rating.domain.FlatPrice;
import io.github.burakboduroglu.ratekit.rating.domain.FreeQuotaThenFlat;
import io.github.burakboduroglu.ratekit.rating.domain.PriceModel;
import io.github.burakboduroglu.ratekit.rating.domain.Tariff;
import io.github.burakboduroglu.ratekit.rating.domain.TieredPrice;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Loads tariff versions and turns the stored JSON parameters into domain price models. */
@Repository
public class TariffRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    TariffRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public List<Tariff> findByMeter(String meter) {
        return jdbc.sql("SELECT id, meter, model, effective_from, params::text AS params "
                        + "FROM tariffs WHERE meter = ?")
                .param(meter)
                .query((rs, row) -> new Tariff(
                        rs.getLong("id"),
                        rs.getString("meter"),
                        rs.getObject("effective_from", OffsetDateTime.class).toInstant(),
                        model(rs.getString("model"), rs.getString("params"))))
                .list();
    }

    /**
     * Stored shapes:
     * FLAT {"rate":"0.05"}; FREE_QUOTA_THEN_FLAT {"freeUnits":100,"rate":"0.10"};
     * TIERED {"tiers":[{"upTo":100,"rate":"0.10"},{"upTo":null,"rate":"0.05"}]}.
     */
    PriceModel model(String name, String paramsJson) {
        JsonNode p;
        try {
            p = json.readTree(paramsJson);
        } catch (java.io.IOException e) {
            throw new UncheckedIOException(e);
        }
        return switch (name) {
            case "FLAT" -> new FlatPrice(rate(p));
            case "FREE_QUOTA_THEN_FLAT" -> new FreeQuotaThenFlat(p.get("freeUnits").asLong(), rate(p));
            case "TIERED" -> {
                List<TieredPrice.Tier> tiers = new ArrayList<>();
                for (JsonNode t : p.get("tiers")) {
                    Long upTo = t.get("upTo") == null || t.get("upTo").isNull() ? null : t.get("upTo").asLong();
                    tiers.add(new TieredPrice.Tier(upTo, rate(t)));
                }
                yield new TieredPrice(tiers);
            }
            default -> throw new IllegalStateException("unknown price model " + name);
        };
    }

    private static BigDecimal rate(JsonNode node) {
        return new BigDecimal(node.get("rate").asText());
    }
}
