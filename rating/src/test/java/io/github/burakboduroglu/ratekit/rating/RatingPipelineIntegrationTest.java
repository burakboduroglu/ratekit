package io.github.burakboduroglu.ratekit.rating;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.burakboduroglu.ratekit.common.Topics;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Whole rating path against real Kafka and PostgreSQL: message in, charge row out.
 * Every test uses its own account and meter, so tests cannot see each other's data.
 */
@SpringBootTest
@Testcontainers
class RatingPipelineIntegrationTest {

    @Container
    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:4.3.1"));

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:17.11-alpine"));

    @TestConfiguration
    static class TopicConfig {
        @Bean
        NewTopic usageEvents() {
            return TopicBuilder.name(Topics.USAGE_EVENTS).partitions(3).replicas(1).build();
        }
    }

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MeterRegistry meters;

    @Test
    void aNewEventBecomesOneCharge() {
        String account = account();
        String meter = flatTariff("0.05");

        send(account, "e1", meter, 3, "2026-10-03T10:00:00Z");

        awaitCharge(account, "e1");
        assertThat(chargeAmount(account, "e1")).isEqualByComparingTo("0.1500");
        assertThat(count("processed_events", account)).isEqualTo(1);
    }

    @Test
    void everyOutcomeAndTheProcessingTimeAreCountedInTheMetrics() {
        String account = account();
        String meter = flatTariff("0.05");
        double ratedBefore = processed("rated");
        double duplicateBefore = processed("duplicate");
        long timedBefore = meters.get("ratekit.rating.duration").timer().count();

        send(account, "m1", meter, 1, "2026-10-03T10:00:00Z");
        send(account, "m1", meter, 1, "2026-10-03T10:00:00Z"); // redelivery
        send(account, "m2", meter, 1, "2026-10-03T10:00:01Z");

        awaitCharge(account, "m2");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(processed("rated")).isGreaterThanOrEqualTo(ratedBefore + 2);
            assertThat(processed("duplicate")).isGreaterThanOrEqualTo(duplicateBefore + 1);
            assertThat(meters.get("ratekit.rating.duration").timer().count()).isGreaterThanOrEqualTo(timedBefore + 3);
        });
    }

    @Test
    void aRedeliveredEventIsRatedOnlyOnce() {
        String account = account();
        String meter = flatTariff("0.05");

        // same event twice, then a marker event on the same account (same partition, so it is
        // processed after both): once the marker is rated, the duplicate has certainly been seen
        send(account, "dup", meter, 1, "2026-10-03T10:00:00Z");
        send(account, "dup", meter, 1, "2026-10-03T10:00:00Z");
        send(account, "marker", meter, 1, "2026-10-03T10:00:01Z");

        awaitCharge(account, "marker");
        assertThat(count("charges", account, "dup")).isEqualTo(1);
        assertThat(count("processed_events", account, "dup")).isEqualTo(1);
    }

    @Test
    void sameEventIdForTwoAccountsIsRatedForBoth() {
        String a = account();
        String b = account();
        String meter = flatTariff("0.01");

        send(a, "shared", meter, 1, "2026-10-03T10:00:00Z");
        send(b, "shared", meter, 1, "2026-10-03T10:00:00Z");

        awaitCharge(a, "shared");
        awaitCharge(b, "shared");
    }

    @Test
    void freeQuotaIsConsumedAcrossEventsInTheSameMonth() {
        String account = account();
        String meter = "quota-" + UUID.randomUUID();
        tariff(meter, "FREE_QUOTA_THEN_FLAT", "2026-01-01T00:00:00Z", "{\"freeUnits\":100,\"rate\":\"0.10\"}");

        send(account, "q1", meter, 95, "2026-10-03T10:00:00Z");
        send(account, "q2", meter, 10, "2026-10-03T11:00:00Z");

        awaitCharge(account, "q2");
        assertThat(chargeAmount(account, "q1")).isEqualByComparingTo("0");
        assertThat(chargeAmount(account, "q2")).isEqualByComparingTo("0.5000"); // 5 free, 5 paid
    }

    @Test
    void theQuotaStartsOverInANewMonth() {
        String account = account();
        String meter = "quota-" + UUID.randomUUID();
        tariff(meter, "FREE_QUOTA_THEN_FLAT", "2026-01-01T00:00:00Z", "{\"freeUnits\":100,\"rate\":\"0.10\"}");

        send(account, "oct", meter, 100, "2026-10-31T23:00:00Z");
        send(account, "nov", meter, 100, "2026-11-01T00:00:00Z");

        awaitCharge(account, "nov");
        assertThat(chargeAmount(account, "nov")).isEqualByComparingTo("0"); // fresh quota in November
    }

    @Test
    void theUsageCounterCountsOnlyChargedUnitsAndEqualsTheSumOfCharges() {
        String account = "acc-" + UUID.randomUUID();
        jdbc.update("INSERT INTO accounts (id, balance) VALUES (?, 1.00)", account);
        String meter = flatTariff("0.10");

        send(account, "c1", meter, 5, "2026-10-03T10:00:00Z");     // 0.50, rated
        send(account, "c1", meter, 5, "2026-10-03T10:00:00Z");     // duplicate, skipped
        send(account, "c2", meter, 8, "2026-10-03T11:00:00Z");     // 0.80 > 0.50 left, rejected
        send(account, "c3", meter, 3, "2026-10-03T12:00:00Z");     // 0.30, rated
        send(account, "c4", meter, 2, "2026-11-02T09:00:00Z");     // 0.20, rated in November

        awaitCharge(account, "c4");
        assertThat(count("rejected_events", account, "c2")).isEqualTo(1);
        List<Map<String, Object>> fromCharges = jdbc.queryForList(
                "SELECT meter, date_trunc('month', occurred_at AT TIME ZONE 'UTC') AT TIME ZONE 'UTC' AS period_start, "
                        + "SUM(quantity) AS units FROM charges WHERE account_id = ? GROUP BY 1, 2 ORDER BY 2", account);
        List<Map<String, Object>> counters = jdbc.queryForList(
                "SELECT meter, period_start, units FROM usage_counters WHERE account_id = ? ORDER BY period_start", account);
        assertThat(counters).hasSize(2);
        assertThat(counters.get(0).get("units")).isEqualTo(8L);  // 5 + 3; the rejected 8 and the duplicate are not counted
        assertThat(counters.get(1).get("units")).isEqualTo(2L);
        for (int i = 0; i < counters.size(); i++) {
            assertThat(((Number) counters.get(i).get("units")).longValue())
                    .isEqualTo(((Number) fromCharges.get(i).get("units")).longValue());
            assertThat(counters.get(i).get("period_start")).isEqualTo(fromCharges.get(i).get("period_start"));
        }
    }

    @Test
    void theTariffVersionOfTheEventTimeIsUsed() {
        String account = account();
        String meter = "versioned-" + UUID.randomUUID();
        tariff(meter, "FLAT", "2026-01-01T00:00:00Z", "{\"rate\":\"0.05\"}");
        tariff(meter, "FLAT", "2026-07-01T00:00:00Z", "{\"rate\":\"0.08\"}");

        send(account, "old", meter, 10, "2026-06-30T23:59:59Z");
        send(account, "new", meter, 10, "2026-07-01T00:00:00Z");

        awaitCharge(account, "new");
        assertThat(chargeAmount(account, "old")).isEqualByComparingTo("0.5000");
        assertThat(chargeAmount(account, "new")).isEqualByComparingTo("0.8000");
    }

    @Test
    void aFailingEventLeavesNoTraceAndDoesNotBlockTheNextOne() {
        String account = account();
        String meter = flatTariff("0.05");
        String ghost = "ghost-" + UUID.randomUUID();          // account that does not exist
        String untariffed = "no-tariff-" + UUID.randomUUID(); // meter without any tariff

        send(ghost, "bad-1", meter, 1, "2026-10-03T10:00:00Z");
        send(account, "bad-2", untariffed, 1, "2026-10-03T10:00:00Z");
        send(account, "good", meter, 1, "2026-10-03T10:00:01Z");

        awaitCharge(account, "good");
        // the transaction rolled back, so neither failed event is marked as processed
        assertThat(count("processed_events", ghost)).isZero();
        assertThat(count("processed_events", account, "bad-2")).isZero();
        assertThat(count("charges", account, "bad-2")).isZero();
    }

    // ---- helpers ----

    private double processed(String outcome) {
        return meters.get("ratekit.events.processed").tag("outcome", outcome).counter().count();
    }

    private String account() {
        String id = "acc-" + UUID.randomUUID();
        jdbc.update("INSERT INTO accounts (id, balance) VALUES (?, 1000)", id);
        return id;
    }

    private String flatTariff(String rate) {
        String meter = "flat-" + UUID.randomUUID();
        tariff(meter, "FLAT", "2026-01-01T00:00:00Z", "{\"rate\":\"" + rate + "\"}");
        return meter;
    }

    private void tariff(String meter, String model, String effectiveFrom, String params) {
        jdbc.update("INSERT INTO tariffs (meter, model, effective_from, params) VALUES (?, ?, ?::timestamptz, ?::jsonb)",
                meter, model, effectiveFrom, params);
    }

    /** Sends the same JSON that the ingest service writes to Kafka, keyed by account. */
    private void send(String account, String eventId, String meter, long quantity, String occurredAt) {
        String body = """
                {"eventId":"%s","accountId":"%s","meter":"%s","quantity":%d,"occurredAt":"%s"}"""
                .formatted(eventId, account, meter, quantity, occurredAt);
        kafka.send(Topics.USAGE_EVENTS, account, body);
    }

    private void awaitCharge(String account, String eventId) {
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(count("charges", account, eventId)).isEqualTo(1));
    }

    private BigDecimal chargeAmount(String account, String eventId) {
        return jdbc.queryForObject("SELECT amount FROM charges WHERE account_id = ? AND event_id = ?",
                BigDecimal.class, account, eventId);
    }

    private int count(String table, String account) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE account_id = ?", Integer.class, account);
    }

    private int count(String table, String account, String eventId) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE account_id = ? AND event_id = ?",
                Integer.class, account, eventId);
    }
}
