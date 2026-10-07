package io.github.burakboduroglu.ratekit.rating.repository;

import io.github.burakboduroglu.ratekit.rating.domain.Tariff;
import io.github.burakboduroglu.ratekit.rating.mapper.TariffMapper;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
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

    /**
     * Stores a new version unless the meter already has one starting at that instant.
     *
     * @return the new id, or empty if that version already exists
     */
    public Optional<Long> insertIfAbsent(String meter, String model, Instant effectiveFrom, String paramsJson) {
        return jdbc.sql("INSERT INTO tariffs (meter, model, effective_from, params) VALUES (?, ?, ?, ?::jsonb) "
                        + "ON CONFLICT (meter, effective_from) DO NOTHING RETURNING id")
                .params(meter, model, OffsetDateTime.ofInstant(effectiveFrom, ZoneOffset.UTC), paramsJson)
                .query(Long.class)
                .optional();
    }

    /** Versions of a meter as stored, oldest first. */
    public List<TariffRow> rowsByMeter(String meter) {
        return jdbc.sql("SELECT id, meter, model, effective_from, params::text AS params "
                        + "FROM tariffs WHERE meter = ? ORDER BY effective_from")
                .param(meter)
                .query((rs, row) -> new TariffRow(
                        rs.getLong("id"),
                        rs.getString("meter"),
                        rs.getString("model"),
                        rs.getObject("effective_from", OffsetDateTime.class).toInstant(),
                        rs.getString("params")))
                .list();
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
