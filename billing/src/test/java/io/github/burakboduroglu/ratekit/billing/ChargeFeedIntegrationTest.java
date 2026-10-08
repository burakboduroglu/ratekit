package io.github.burakboduroglu.ratekit.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.github.burakboduroglu.ratekit.billing.exception.ChargesInFlightException;
import io.github.burakboduroglu.ratekit.billing.service.InvoiceRunService;
import io.github.burakboduroglu.ratekit.billing.service.InvoiceService;
import io.github.burakboduroglu.ratekit.common.BillingPeriod;
import io.github.burakboduroglu.ratekit.common.ChargeEvent;
import io.github.burakboduroglu.ratekit.common.ChargeFeedWatermark;
import io.github.burakboduroglu.ratekit.common.Money;
import io.github.burakboduroglu.ratekit.common.Topics;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The charge feed into billing's own database (ADR 0019), against real Kafka and PostgreSQL. The test
 * plays rating's relay: it writes charges and progress markers to the {@code charges} topic in the
 * same format. The clock is fixed at 15 June 2027 12:00 UTC; the usage topic does not exist, so the
 * rating check passes and only the charge feed decides.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "ratekit.billing.rating-progress.charge-feed-wait=PT1S",
        "ratekit.billing.rating-progress.clock-skew-margin=PT1S"})
@Testcontainers
class ChargeFeedIntegrationTest {

    @Container
    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:4.3.1"));

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:17.11-alpine"));

    private static final Instant NOW = Instant.parse("2027-06-15T12:00:00Z");

    @TestConfiguration
    static class Setup {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        /** rating creates the topic in production. */
        @Bean
        NewTopic charges() {
            return TopicBuilder.name(Topics.CHARGES).partitions(3).replicas(1).build();
        }
    }

    @Autowired
    InvoiceRunService runs;

    @Autowired
    InvoiceService invoices;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TestRestTemplate http;

    private KafkaProducer<String, Object> relay;

    @BeforeEach
    void playRatingsRelay() {
        JsonSerializer<Object> json = new JsonSerializer<>();
        json.configure(Map.of(JsonSerializer.TYPE_MAPPINGS, Topics.CHARGES_TYPE_MAPPING), false);
        relay = new KafkaProducer<>(Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()),
                new StringSerializer(), json);
    }

    @AfterEach
    void close() {
        relay.close();
    }

    @Test
    void aChargeDeliveredTwiceIsStoredOnce() {
        String account = "dup-" + UUID.randomUUID();
        ChargeEvent charge = charge(account, "e1", "0.0500", "2026-09-10T10:00:00Z");

        send(charge);
        send(charge); // redelivery: the relay sent it again after a failed acknowledgement
        send(charge(account, "marker", "0.0100", "2026-09-10T10:00:01Z")); // same key, same partition, read after both

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(count("account_id = ? AND event_id = 'marker'", account)).isEqualTo(1));
        assertThat(count("account_id = ? AND event_id = 'e1'", account)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT amount FROM charges WHERE account_id = ? AND event_id = 'e1'",
                BigDecimal.class, account)).isEqualByComparingTo("0.0500");
    }

    @Test
    void aMonthIsNotInvoicedWhileChargesMayStillBeOnTheirWayAndIsOnceEveryPartitionHasANewerMarker() {
        String account = "feed-" + UUID.randomUUID();
        BillingPeriod september = BillingPeriod.of(YearMonth.of(2026, 9));
        send(charge(account, "e1", "0.2500", "2026-09-15T10:00:00Z"));
        // markers from before billing's check: they say nothing about charges rated since
        for (int partition = 0; partition < 3; partition++) {
            marker(partition, NOW.minusSeconds(60));
        }
        awaitMarkers(3, NOW.minusSeconds(60));

        assertThatThrownBy(() -> runs.run(september)).isInstanceOf(ChargesInFlightException.class);
        assertThat(post("{\"period\":\"2026-09\"}").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // newer markers on two partitions out of three: the third may still hold a charge
        marker(0, NOW.plusSeconds(5));
        marker(1, NOW.plusSeconds(5));
        awaitMarkers(2, NOW.plusSeconds(5));
        assertThatThrownBy(() -> runs.run(september)).isInstanceOf(ChargesInFlightException.class);
        assertThat(invoices.find(account, september)).isEmpty();

        marker(2, NOW.plusSeconds(5));
        awaitMarkers(3, NOW.plusSeconds(5));
        assertThat(runs.run(september).created()).isGreaterThanOrEqualTo(1);
        assertThat(invoices.find(account, september).orElseThrow().total()).isEqualTo(Money.of("0.25"));
    }

    // ---- helpers ----

    private static ChargeEvent charge(String account, String eventId, String amount, String occurredAt) {
        return new ChargeEvent(eventId, account, "sms", 5, new BigDecimal(amount), Instant.parse(occurredAt),
                Instant.parse(occurredAt).plusSeconds(1));
    }

    private void send(ChargeEvent charge) {
        relay.send(new ProducerRecord<>(Topics.CHARGES, charge.accountId(), charge));
        relay.flush();
    }

    private void marker(int partition, Instant publishedThrough) {
        relay.send(new ProducerRecord<>(Topics.CHARGES, partition, null, new ChargeFeedWatermark(publishedThrough, 3)));
        relay.flush();
    }

    private void awaitMarkers(int partitions, Instant atLeast) {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM charge_feed_watermarks WHERE published_through >= ?::timestamptz",
                Integer.class, atLeast.toString())).isEqualTo(partitions));
    }

    private int count(String where, String account) {
        return jdbc.queryForObject("SELECT count(*) FROM charges WHERE " + where, Integer.class, account);
    }

    private org.springframework.http.ResponseEntity<String> post(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.postForEntity("/v1/invoice-runs", new HttpEntity<>(body, headers), String.class);
    }
}
