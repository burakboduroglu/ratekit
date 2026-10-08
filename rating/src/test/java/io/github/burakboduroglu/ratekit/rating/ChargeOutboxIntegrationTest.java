package io.github.burakboduroglu.ratekit.rating;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.github.burakboduroglu.ratekit.common.ChargeEvent;
import io.github.burakboduroglu.ratekit.common.ChargeFeedWatermark;
import io.github.burakboduroglu.ratekit.common.Topics;
import io.github.burakboduroglu.ratekit.common.UsageEvent;
import io.github.burakboduroglu.ratekit.rating.service.RatingService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The charge outbox and its relay (ADR 0019) against real PostgreSQL and Kafka: a charge and its outbox
 * row commit or roll back together, and the relay writes the charges to the topic in order, marks them
 * sent and then writes a progress marker on every partition. Events are handed to the rating service
 * directly, so each test controls exactly what is charged.
 */
@SpringBootTest(properties = {
        "ratekit.rating.charge-feed.poll-interval=PT0.1S",
        "ratekit.rating.charge-feed.watermark-interval=PT0.1S"})
@Testcontainers
class ChargeOutboxIntegrationTest {

    @Container
    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:4.3.1"));

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:17.11-alpine"));

    @Autowired
    RatingService rating;

    @Autowired
    JdbcTemplate jdbc;

    private KafkaConsumer<String, Object> feed;

    @BeforeEach
    void readTheFeedFromTheStart() {
        JsonDeserializer<Object> json = new JsonDeserializer<>();
        json.configure(Map.of(JsonDeserializer.TYPE_MAPPINGS, Topics.CHARGES_TYPE_MAPPING,
                JsonDeserializer.TRUSTED_PACKAGES, "io.github.burakboduroglu.ratekit.common"), false);
        feed = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"),
                new StringDeserializer(), json);
        feed.subscribe(List.of(Topics.CHARGES));
    }

    @AfterEach
    void close() {
        feed.close();
    }

    @Test
    void aStoredChargeIsQueuedInTheOutboxInTheSameTransaction() {
        String account = account("acc");
        String meter = flatTariff();

        rating.handle(event(account, "e1", meter, "2026-10-03T10:00:00Z"));

        Long chargeId = jdbc.queryForObject("SELECT id FROM charges WHERE account_id = ?", Long.class, account);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM charge_outbox WHERE charge_id = ?", Integer.class, chargeId))
                .isEqualTo(1);
    }

    @Test
    void aRollbackAfterTheOutboxRowLeavesNeitherTheChargeNorTheRow() {
        // the usage counter is written after the charge and the outbox row; make it fail for one account
        String account = account("boom");
        String meter = flatTariff();
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION fail_for_boom() RETURNS trigger AS $$
                BEGIN RAISE EXCEPTION 'counter refused for %', NEW.account_id; END $$ LANGUAGE plpgsql""");
        jdbc.execute("DROP TRIGGER IF EXISTS fail_for_boom ON usage_counters");
        jdbc.execute("CREATE TRIGGER fail_for_boom BEFORE INSERT ON usage_counters FOR EACH ROW "
                + "WHEN (NEW.account_id LIKE 'boom-%') EXECUTE FUNCTION fail_for_boom()");
        int outboxBefore = jdbc.queryForObject("SELECT count(*) FROM charge_outbox", Integer.class);
        try {
            assertThatThrownBy(() -> rating.handle(event(account, "e1", meter, "2026-10-03T10:00:00Z")))
                    .hasMessageContaining("counter refused");
        } finally {
            jdbc.execute("DROP TRIGGER fail_for_boom ON usage_counters");
        }

        assertThat(jdbc.queryForObject("SELECT count(*) FROM charges WHERE account_id = ?", Integer.class, account)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM charge_outbox", Integer.class)).isEqualTo(outboxBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE account_id = ?", Integer.class, account))
                .isZero();
    }

    @Test
    void theRelayPublishesAnAccountsChargesInOrderMarksThemSentAndThenWritesAMarker() {
        String account = account("relay");
        String meter = flatTariff();
        for (int i = 1; i <= 3; i++) {
            rating.handle(event(account, "e" + i, meter, "2026-10-03T10:00:0" + i + "Z"));
        }
        Instant lastRated = jdbc.queryForObject("SELECT max(created_at) FROM charges WHERE account_id = ?",
                java.sql.Timestamp.class, account).toInstant();

        List<ConsumerRecord<String, Object>> charges = new ArrayList<>();
        Map<Integer, ChargeFeedWatermark> markers = new HashMap<>();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            for (ConsumerRecord<String, Object> record : feed.poll(Duration.ofMillis(200))) {
                if (record.value() instanceof ChargeEvent charge && charge.accountId().equals(account)) {
                    charges.add(record);
                } else if (record.value() instanceof ChargeFeedWatermark marker) {
                    markers.put(record.partition(), marker);
                }
            }
            assertThat(charges).hasSize(3);
            // a marker newer than the last charge, on every partition, written after that charge
            assertThat(markers).hasSize(3);
            assertThat(markers.values()).allSatisfy(m -> {
                assertThat(m.partitions()).isEqualTo(3);
                assertThat(m.publishedThrough()).isAfter(lastRated);
            });
        });

        assertThat(charges).extracting(r -> ((ChargeEvent) r.value()).eventId()).containsExactly("e1", "e2", "e3");
        assertThat(charges).allSatisfy(r -> {
            assertThat(r.key()).isEqualTo(account);
            assertThat(r.partition()).isEqualTo(charges.get(0).partition());
            assertThat(r.timestamp()).isEqualTo(((ChargeEvent) r.value()).ratedAt().toEpochMilli());
        });
        assertThat(((ChargeEvent) charges.get(0).value()).amount()).isEqualByComparingTo("0.0500");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM charge_outbox o JOIN charges c ON c.id = o.charge_id "
                + "WHERE c.account_id = ? AND o.sent_at IS NOT NULL", Integer.class, account)).isEqualTo(3);
    }

    // ---- helpers ----

    private String account(String prefix) {
        String id = prefix + "-" + UUID.randomUUID();
        jdbc.update("INSERT INTO accounts (id, balance) VALUES (?, 1000)", id);
        return id;
    }

    private String flatTariff() {
        String meter = "flat-" + UUID.randomUUID();
        jdbc.update("INSERT INTO tariffs (meter, model, effective_from, params) "
                + "VALUES (?, 'FLAT', '2026-01-01T00:00:00Z', '{\"rate\":\"0.05\"}'::jsonb)", meter);
        return meter;
    }

    private static UsageEvent event(String account, String eventId, String meter, String occurredAt) {
        return new UsageEvent(eventId, account, meter, 1, Instant.parse(occurredAt));
    }
}
