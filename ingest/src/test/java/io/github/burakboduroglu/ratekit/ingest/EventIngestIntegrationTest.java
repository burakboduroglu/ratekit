package io.github.burakboduroglu.ratekit.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.burakboduroglu.ratekit.common.Topics;
import io.github.burakboduroglu.ratekit.common.UsageEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

// The clock is fixed so the event dates below stay inside the accepted time window for good.
// @SpringBootTest switches metric export off; turn it on to test the Prometheus endpoint
@AutoConfigureObservability
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class EventIngestIntegrationTest {

    @Container
    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:4.3.1"));

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-10-03T12:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Autowired
    TestRestTemplate http;

    @Autowired
    ObjectMapper json;

    @Test
    void validEventIsAcceptedAndLandsInKafkaKeyedByAccount() throws Exception {
        String body = """
                {"eventId":"evt-1","accountId":"acc-1","meter":"sms","quantity":3,"occurredAt":"2026-10-03T10:00:00Z"}""";

        ResponseEntity<String> response = http.postForEntity("/v1/events", jsonRequest(body), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).contains("\"eventId\":\"evt-1\"").contains("\"status\":\"ACCEPTED\"");
        // the topic is shared with the other tests, so find our record by content
        ConsumerRecord<String, String> record = consumeAll().stream()
                .filter(r -> r.value().contains("\"evt-1\""))
                .findFirst()
                .orElseThrow(() -> new AssertionError("evt-1 was not found in " + Topics.USAGE_EVENTS));
        assertThat(record.key()).isEqualTo("acc-1");
        assertThat(record.value()).contains("\"occurredAt\":\"2026-10-03T10:00:00Z\"");
        UsageEvent event = json.readValue(record.value(), UsageEvent.class);
        assertThat(event.eventId()).isEqualTo("evt-1");
        assertThat(event.quantity()).isEqualTo(3);
    }

    @Test
    void sameAccountAlwaysGoesToTheSamePartition() throws Exception {
        String account = "acc-" + UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            String body = """
                    {"eventId":"p-%d","accountId":"%s","meter":"sms","quantity":1,"occurredAt":"2026-10-03T10:00:00Z"}"""
                    .formatted(i, account);
            assertThat(http.postForEntity("/v1/events", jsonRequest(body), Void.class).getStatusCode())
                    .isEqualTo(HttpStatus.ACCEPTED);
        }

        List<Integer> partitions = consumeAll().stream()
                .filter(r -> r.key().equals(account))
                .map(ConsumerRecord::partition)
                .toList();

        assertThat(partitions).hasSize(5);
        assertThat(partitions).containsOnly(partitions.get(0));
    }

    @Test
    void invalidEventIsRejectedAndNothingIsPublished() {
        String body = """
                {"eventId":"","accountId":"acc-1","meter":"sms","quantity":0,"occurredAt":"2026-10-03T10:00:00Z"}""";

        ResponseEntity<String> response = http.postForEntity("/v1/events", jsonRequest(body), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void anEventFromAClosedMonthIsRefusedWith422AndTheReason() {
        String body = """
                {"eventId":"late-1","accountId":"acc-1","meter":"sms","quantity":1,"occurredAt":"2026-09-30T23:00:00Z"}""";

        ResponseEntity<String> response = http.postForEntity("/v1/events", jsonRequest(body), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).contains("closed billing month");
        assertThat(consumeAll()).noneMatch(r -> r.value().contains("\"late-1\""));
    }

    @Test
    void actuatorExposesHealthAndTheAcceptedEventCounterButNothingThatChangesState() {
        String body = """
                {"eventId":"metrics-1","accountId":"acc-metrics","meter":"sms","quantity":1,"occurredAt":"2026-10-03T10:00:00Z"}""";
        assertThat(http.postForEntity("/v1/events", jsonRequest(body), Void.class).getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);

        ResponseEntity<String> health = http.getForEntity("/actuator/health", String.class);
        String metrics = http.getForEntity("/actuator/prometheus", String.class).getBody();

        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(health.getBody()).contains("UP");
        assertThat(metrics).contains("ratekit_events_accepted_total").contains("application=\"ratekit-ingest\"");
        assertThat(http.getForEntity("/actuator/env", String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(http.getForEntity("/actuator/heapdump", String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void openApiSpecDescribesTheEndpoint() {
        ResponseEntity<String> spec = http.getForEntity("/v3/api-docs", String.class);

        assertThat(spec.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(spec.getBody()).contains("/v1/events").contains("Submit a usage event");
    }

    private static org.springframework.http.HttpEntity<String> jsonRequest(String body) {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return new org.springframework.http.HttpEntity<>(body, headers);
    }

    /** Reads the topic from the beginning with a throwaway consumer group. */
    private static List<ConsumerRecord<String, String>> consumeAll() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        List<ConsumerRecord<String, String>> out = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer =
                new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(Topics.USAGE_EVENTS));
            long deadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < deadline) {
                consumer.poll(Duration.ofMillis(500)).forEach(out::add);
                if (!out.isEmpty() && System.currentTimeMillis() > deadline - 8_000) {
                    break;
                }
            }
        }
        return out;
    }
}
