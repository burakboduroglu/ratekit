package io.github.burakboduroglu.ratekit.rating;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.burakboduroglu.ratekit.rating.service.RetentionService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * The retention cleanup against a real PostgreSQL. The clock is fixed at 15 June 2027, so with the
 * default 90 days anything rejected before 17 March 2027 is old. The batch size is 2, so several
 * batches run.
 */
// no Kafka container here: keep the listener from connecting to whatever runs on localhost:9092
@SpringBootTest(properties = {"spring.kafka.listener.auto-startup=false", "ratekit.rating.retention.batch-size=2"})
@Testcontainers
class RetentionIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:17.11-alpine"));

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2027-06-15T12:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Autowired
    RetentionService retention;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MeterRegistry meters;

    @Test
    void oldRejectionsGoWithTheirIdempotencyRowsAndEverythingElseStays() {
        String account = account();
        for (int i = 0; i < 5; i++) {
            rejected(account, "old-" + i, "2027-01-0" + (i + 1) + "T10:00:00Z");
        }
        rejected(account, "recent", "2027-06-01T10:00:00Z");
        rejected(account, "edge-kept", "2027-03-17T12:00:01Z"); // just inside 90 days
        charged(account, "rated-long-ago", "2026-01-05T10:00:00Z");
        double before = deleted("rejected_events");

        int removed = retention.pruneRejected();

        assertThat(removed).isEqualTo(5);
        assertThat(eventIds("rejected_events", account)).containsExactlyInAnyOrder("recent", "edge-kept");
        assertThat(eventIds("processed_events", account))
                .containsExactlyInAnyOrder("recent", "edge-kept", "rated-long-ago");
        assertThat(eventIds("charges", account)).containsExactly("rated-long-ago");
        assertThat(deleted("rejected_events") - before).isEqualTo(5.0);
    }

    @Test
    void aSecondRunFindsNothingToDo() {
        String account = account();
        rejected(account, "old", "2027-01-01T10:00:00Z");
        retention.pruneRejected();

        assertThat(retention.pruneRejected()).isZero();
    }

    // ---- helpers ----

    private String account() {
        String id = "acc-" + UUID.randomUUID();
        jdbc.update("INSERT INTO accounts (id, balance) VALUES (?, 0)", id);
        return id;
    }

    private void rejected(String account, String eventId, String rejectedAt) {
        jdbc.update("INSERT INTO processed_events (account_id, event_id, processed_at) VALUES (?, ?, ?::timestamptz)",
                account, eventId, rejectedAt);
        jdbc.update("INSERT INTO rejected_events (account_id, event_id, meter, quantity, amount, reason, occurred_at, rejected_at) "
                + "VALUES (?, ?, 'sms', 1, 0.05, 'INSUFFICIENT_BALANCE', ?::timestamptz, ?::timestamptz)",
                account, eventId, rejectedAt, rejectedAt);
    }

    private void charged(String account, String eventId, String at) {
        String meter = "m-" + UUID.randomUUID();
        Long tariff = jdbc.queryForObject("INSERT INTO tariffs (meter, model, effective_from, params) "
                + "VALUES (?, 'FLAT', '2026-01-01T00:00:00Z', '{\"rate\":\"0.05\"}') RETURNING id", Long.class, meter);
        jdbc.update("INSERT INTO processed_events (account_id, event_id, processed_at) VALUES (?, ?, ?::timestamptz)",
                account, eventId, at);
        jdbc.update("INSERT INTO charges (account_id, event_id, meter, quantity, amount, tariff_id, occurred_at) "
                + "VALUES (?, ?, ?, 1, 0.05, ?, ?::timestamptz)", account, eventId, meter, tariff, at);
    }

    private java.util.List<String> eventIds(String table, String account) {
        return jdbc.queryForList("SELECT event_id FROM " + table + " WHERE account_id = ?", String.class, account);
    }

    private double deleted(String table) {
        return meters.get("ratekit.retention.deleted").tag("table", table).counter().count();
    }
}
