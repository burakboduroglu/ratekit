package io.github.burakboduroglu.ratekit.rating;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.burakboduroglu.ratekit.common.Topics;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/** The tariff API over HTTP, and proof that a tariff added through it prices real events. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class TariffApiIntegrationTest {

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
    TestRestTemplate http;

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void aTariffAddedThroughTheApiPricesTheNextEvent() {
        String meter = meter();
        String account = "acc-" + UUID.randomUUID();
        post("/v1/accounts", "{\"accountId\":\"" + account + "\"}");
        post("/v1/accounts/" + account + "/top-ups", "{\"topUpId\":\"t1\",\"amount\":\"10\"}");

        ResponseEntity<String> created = addTariff(meter, "FREE_QUOTA_THEN_FLAT", null, "{\"freeUnits\":2,\"rate\":\"0.05\"}");
        Instant after = Instant.now().plusSeconds(1);
        kafka.send(Topics.USAGE_EVENTS, account, """
                {"eventId":"e1","accountId":"%s","meter":"%s","quantity":5,"occurredAt":"%s"}"""
                .formatted(account, meter, after));

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        await().atMost(Duration.ofSeconds(30)).ignoreExceptions().untilAsserted(() -> assertThat(
                jdbc.queryForObject("SELECT amount FROM charges WHERE account_id = ? AND event_id = 'e1'", BigDecimal.class, account))
                .isEqualByComparingTo("0.15")); // 2 free, 3 at 0.05
        assertThat(jdbc.queryForObject("SELECT balance FROM accounts WHERE id = ?", BigDecimal.class, account))
                .isEqualByComparingTo("9.85");
    }

    @Test
    void parametersRatingCouldNotPriceWithAreRefusedAndNothingIsStored() {
        String meter = meter();

        assertThat(addTariff(meter, "FLAT", null, "{}").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(addTariff(meter, "FLAT", null, "{\"rate\":\"-1\"}").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(addTariff(meter, "FREE_QUOTA_THEN_FLAT", null, "{\"freeUnits\":\"abc\",\"rate\":\"0.05\"}").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        ResponseEntity<String> unknown = addTariff(meter, "MAGIC", null, "{\"rate\":\"1\"}");
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(unknown.getBody()).contains("unknown price model MAGIC");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tariffs WHERE meter = ?", Integer.class, meter)).isZero();
    }

    @Test
    void aVersionStartingInThePastIsRefused() {
        ResponseEntity<String> response = addTariff(meter(), "FLAT", "\"2026-01-01T00:00:00Z\"", "{\"rate\":\"1\"}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).contains("retroactively");
    }

    @Test
    void twoVersionsCannotStartAtTheSameInstant() {
        String meter = meter();
        String from = "\"" + Instant.now().plusSeconds(3600) + "\"";

        assertThat(addTariff(meter, "FLAT", from, "{\"rate\":\"1\"}").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(addTariff(meter, "FLAT", from, "{\"rate\":\"2\"}").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void versionsAreListedOldestFirstWithTheirParameters() {
        String meter = meter();
        addTariff(meter, "FLAT", "\"" + Instant.now().plusSeconds(7200) + "\"", "{\"rate\":\"0.02\"}");
        addTariff(meter, "TIERED", "\"" + Instant.now().plusSeconds(3600) + "\"",
                "{\"tiers\":[{\"upTo\":100,\"rate\":\"0.10\"},{\"upTo\":null,\"rate\":\"0.05\"}]}");

        String body = http.getForEntity("/v1/tariffs?meter=" + meter, String.class).getBody();

        assertThat(body.indexOf("TIERED")).isLessThan(body.indexOf("FLAT"));
        assertThat(body).contains("\"upTo\":100").contains("\"rate\":\"0.02\"");
        assertThat(http.getForEntity("/v1/tariffs?meter=none-" + UUID.randomUUID(), String.class).getBody()).isEqualTo("[]");
    }

    @Test
    void aNewVersionIsUsedAtOnceEvenWhileTheOldOneIsCached() {
        String meter = meter();
        jdbc.update("INSERT INTO tariffs (meter, model, effective_from, params) "
                + "VALUES (?, 'FLAT', '2026-01-01T00:00:00Z'::timestamptz, '{\"rate\":\"0.05\"}'::jsonb)", meter);
        String account = "acc-" + UUID.randomUUID();
        post("/v1/accounts", "{\"accountId\":\"" + account + "\"}");
        post("/v1/accounts/" + account + "/top-ups", "{\"topUpId\":\"t1\",\"amount\":\"10\"}");

        sendEvent(account, "before", meter, Instant.now());
        awaitChargeOf(account, "before", "0.05");           // rating has now cached the 0.05 version
        assertThat(addTariff(meter, "FLAT", null, "{\"rate\":\"0.08\"}").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        sendEvent(account, "after", meter, Instant.now().plusSeconds(1));

        awaitChargeOf(account, "after", "0.08");            // well inside the 30 s TTL
    }

    // ---- helpers ----

    private void sendEvent(String account, String eventId, String meter, Instant occurredAt) {
        kafka.send(Topics.USAGE_EVENTS, account, """
                {"eventId":"%s","accountId":"%s","meter":"%s","quantity":1,"occurredAt":"%s"}"""
                .formatted(eventId, account, meter, occurredAt));
    }

    private void awaitChargeOf(String account, String eventId, String amount) {
        await().atMost(Duration.ofSeconds(30)).ignoreExceptions().untilAsserted(() -> assertThat(
                jdbc.queryForObject("SELECT amount FROM charges WHERE account_id = ? AND event_id = ?",
                        BigDecimal.class, account, eventId))
                .isEqualByComparingTo(amount));
    }

    private static String meter() {
        return "m-" + UUID.randomUUID();
    }

    private ResponseEntity<String> addTariff(String meter, String model, String effectiveFromJson, String paramsJson) {
        String from = effectiveFromJson == null ? "" : ",\"effectiveFrom\":" + effectiveFromJson;
        return post("/v1/tariffs", "{\"meter\":\"" + meter + "\",\"model\":\"" + model + "\"" + from
                + ",\"params\":" + paramsJson + "}");
    }

    private ResponseEntity<String> post(String path, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.postForEntity(path, new HttpEntity<>(body, headers), String.class);
    }
}
