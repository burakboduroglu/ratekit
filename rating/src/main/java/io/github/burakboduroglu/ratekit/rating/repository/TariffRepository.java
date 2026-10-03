package io.github.burakboduroglu.ratekit.rating.repository;

import io.github.burakboduroglu.ratekit.rating.domain.Tariff;
import io.github.burakboduroglu.ratekit.rating.mapper.TariffMapper;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reads tariff versions from the database; the row to domain conversion belongs to {@link TariffMapper}. */
@Repository
public class TariffRepository {

    private final JdbcClient jdbc;
    private final TariffMapper mapper;

    TariffRepository(JdbcClient jdbc, TariffMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public List<Tariff> findByMeter(String meter) {
        return jdbc.sql("SELECT id, meter, model, effective_from, params::text AS params "
                        + "FROM tariffs WHERE meter = ?")
                .param(meter)
                .query((rs, row) -> mapper.toTariff(
                        rs.getLong("id"),
                        rs.getString("meter"),
                        rs.getString("model"),
                        rs.getObject("effective_from", OffsetDateTime.class).toInstant(),
                        rs.getString("params")))
                .list();
    }
}
