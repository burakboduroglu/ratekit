package io.github.burakboduroglu.ratekit.rating;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * The usage counter migration on a database that already holds charges: it must start every counter
 * at the sum of the charges stored before it, month by month in UTC. Runs Flyway directly, without
 * Spring, so it can stop at version 3, add data, and only then apply version 4.
 */
@Testcontainers
class UsageCounterBackfillTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:17.11-alpine"));

    @Test
    void existingChargesBecomeTheStartingCounters() {
        DriverManagerDataSource db = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(db);
        flyway(db, "3").migrate();

        jdbc.update("INSERT INTO accounts (id, balance) VALUES ('a', 100)");
        long tariff = jdbc.queryForObject("INSERT INTO tariffs (meter, model, effective_from, params) "
                + "VALUES ('sms', 'FLAT', '2026-01-01T00:00:00Z', '{\"rate\":\"0.01\"}') RETURNING id", Long.class);
        charge(jdbc, "e1", 5, "2026-09-10T10:00:00Z", tariff);
        charge(jdbc, "e2", 7, "2026-09-30T21:59:59Z", tariff);   // still September in UTC
        charge(jdbc, "e3", 4, "2026-09-30T22:00:00-02:00", tariff); // 1 October 00:00 UTC

        flyway(db, "4").migrate();

        List<Map<String, Object>> counters = jdbc.queryForList(
                "SELECT to_char(period_start AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI') AS period, units "
                        + "FROM usage_counters WHERE account_id = 'a' AND meter = 'sms' ORDER BY period_start");
        assertThat(counters).containsExactly(
                Map.of("period", "2026-09-01 00:00", "units", 12L),
                Map.of("period", "2026-10-01 00:00", "units", 4L));
    }

    private static Flyway flyway(DriverManagerDataSource db, String target) {
        return Flyway.configure().dataSource(db).locations("classpath:db/migration").target(target).load();
    }

    private static void charge(JdbcTemplate jdbc, String eventId, long quantity, String occurredAt, long tariff) {
        jdbc.update("INSERT INTO processed_events (account_id, event_id) VALUES ('a', ?)", eventId);
        jdbc.update("INSERT INTO charges (account_id, event_id, meter, quantity, amount, tariff_id, occurred_at) "
                + "VALUES ('a', ?, 'sms', ?, 0, ?, ?::timestamptz)", eventId, quantity, tariff, occurredAt);
    }
}
