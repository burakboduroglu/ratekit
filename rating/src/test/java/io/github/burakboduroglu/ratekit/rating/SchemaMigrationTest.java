package io.github.burakboduroglu.ratekit.rating;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** Applies the Flyway migrations to a clean PostgreSQL and proves the safety constraints hold. */
// no Kafka container here: keep the listener from connecting to whatever runs on localhost:9092
@SpringBootTest(properties = "spring.kafka.listener.auto-startup=false")
@Testcontainers
class SchemaMigrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:17.11-alpine"));

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void migrationCreatesAllTables() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'", String.class);

        assertThat(tables).contains("accounts", "tariffs", "processed_events", "charges", "rejected_events",
                "flyway_schema_history");
    }

    @Test
    void migrationIsRecordedAsSuccessful() {
        Boolean success = jdbc.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = '1'", Boolean.class);

        assertThat(success).isTrue();
    }

    @Test
    void sameEventCannotBeRecordedTwice() {
        account("acc-dup");
        jdbc.update("INSERT INTO processed_events (account_id, event_id) VALUES ('acc-dup', 'e1')");

        assertThatThrownBy(() ->
                jdbc.update("INSERT INTO processed_events (account_id, event_id) VALUES ('acc-dup', 'e1')"))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void insertOnConflictDoNothingReportsTheDuplicateAsZeroRows() {
        account("acc-skip");
        String sql = "INSERT INTO processed_events (account_id, event_id) VALUES ('acc-skip', 'e1') "
                + "ON CONFLICT DO NOTHING";

        assertThat(jdbc.update(sql)).isEqualTo(1);
        assertThat(jdbc.update(sql)).isZero();
    }

    @Test
    void sameEventIdForADifferentAccountIsAllowed() {
        account("acc-a");
        account("acc-b");

        jdbc.update("INSERT INTO processed_events (account_id, event_id) VALUES ('acc-a', 'shared')");
        jdbc.update("INSERT INTO processed_events (account_id, event_id) VALUES ('acc-b', 'shared')");

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM processed_events WHERE event_id = 'shared'", Integer.class)).isEqualTo(2);
    }

    @Test
    void balanceCanNeverGoNegative() {
        account("acc-neg");

        assertThatThrownBy(() -> jdbc.update("UPDATE accounts SET balance = -0.0001 WHERE id = 'acc-neg'"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aTariffVersionIsUniquePerMeterAndStartTime() {
        String sql = "INSERT INTO tariffs (meter, model, effective_from, params) "
                + "VALUES ('sms', 'FLAT', '2026-01-01T00:00:00Z', '{\"rate\":\"0.05\"}')";
        jdbc.update(sql);

        assertThatThrownBy(() -> jdbc.update(sql)).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void unknownPriceModelIsRejected() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO tariffs (meter, model, effective_from, params) "
                + "VALUES ('sms', 'MAGIC', '2026-02-01T00:00:00Z', '{}')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aChargeNeedsAProcessedEvent() {
        account("acc-chg");
        long tariffId = jdbc.queryForObject("INSERT INTO tariffs (meter, model, effective_from, params) "
                + "VALUES ('data-mb', 'FLAT', '2026-03-01T00:00:00Z', '{}') RETURNING id", Long.class);

        assertThatThrownBy(() -> jdbc.update("INSERT INTO charges "
                + "(account_id, event_id, meter, quantity, amount, tariff_id, occurred_at) "
                + "VALUES ('acc-chg', 'never-processed', 'data-mb', 1, 1, ?, now())", tariffId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aChargeIsAcceptedOnceTheEventIsProcessedButNotTwice() {
        account("acc-ok");
        long tariffId = jdbc.queryForObject("INSERT INTO tariffs (meter, model, effective_from, params) "
                + "VALUES ('voice-min', 'FLAT', '2026-04-01T00:00:00Z', '{}') RETURNING id", Long.class);
        jdbc.update("INSERT INTO processed_events (account_id, event_id) VALUES ('acc-ok', 'e1')");
        String sql = "INSERT INTO charges (account_id, event_id, meter, quantity, amount, tariff_id, occurred_at) "
                + "VALUES ('acc-ok', 'e1', 'voice-min', 2, 0.1000, " + tariffId + ", now())";

        assertThat(jdbc.update(sql)).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update(sql)).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void aRejectedEventNeedsAProcessedEventAndIsRecordedOnce() {
        account("acc-rej");
        String sql = "INSERT INTO rejected_events (account_id, event_id, meter, quantity, amount, reason, occurred_at) "
                + "VALUES ('acc-rej', 'e1', 'sms', 1, 1, 'INSUFFICIENT_BALANCE', now())";

        assertThatThrownBy(() -> jdbc.update(sql)).isInstanceOf(DataIntegrityViolationException.class);

        jdbc.update("INSERT INTO processed_events (account_id, event_id) VALUES ('acc-rej', 'e1')");
        assertThat(jdbc.update(sql)).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update(sql)).isInstanceOf(DuplicateKeyException.class);
    }

    private void account(String id) {
        jdbc.update("INSERT INTO accounts (id, balance) VALUES (?, 10)", id);
    }
}
