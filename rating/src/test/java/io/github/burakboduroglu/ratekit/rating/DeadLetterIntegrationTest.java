package io.github.burakboduroglu.ratekit.rating;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.github.burakboduroglu.ratekit.common.Topics;
import io.github.burakboduroglu.ratekit.common.UsageEvent;
import io.github.burakboduroglu.ratekit.rating.service.RatingService;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Retry and dead-letter behaviour against real Kafka and PostgreSQL. The pauses between retries are
 * shortened so the tests stay fast. Bad and good events are sent to the same partition, so each
 * test also proves that a failing event does not hold up the ones behind it.
 */
@SpringBootTest(properties = {
        "ratekit.rating.retry.max-retries=3",
        "ratekit.rating.retry.initial-interval-ms=50",
        "ratekit.rating.retry.max-interval-ms=200"})
@Testcontainers
class DeadLetterIntegrationTest {

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

    @MockitoSpyBean
    RatingService rating;

    @Test
    void anUnknownAccountIsDeadLetteredAtOnceWithoutBlockingTheNextEvent() {
        String ghost = "ghost-" + UUID.randomUUID();
        String account = account();
        String meter = flatTariff();

        send(ghost, "bad", meter);
        send(account, "good", meter);

        awaitCharge(account, "good");
        ConsumerRecord<String, String> dead = awaitDeadLetter("bad");
        assertThat(dead.key()).isEqualTo(ghost);
        assertThat(causeOf(dead)).endsWith("UnknownAccountException");
        assertThat(header(dead, KafkaHeaders.DLT_ORIGINAL_TOPIC)).isEqualTo(Topics.USAGE_EVENTS);
        verify(rating, times(1)).handle(argThat(e -> e != null && e.eventId().equals("bad"))); // never retried
    }

    @Test
    void aMeterWithoutATariffIsDeadLetteredAtOnce() {
        String account = account();
        String meter = flatTariff();

        send(account, "no-tariff", "meter-without-tariff-" + UUID.randomUUID());
        send(account, "good", meter);

        awaitCharge(account, "good");
        assertThat(causeOf(awaitDeadLetter("no-tariff"))).endsWith("NoTariffException");
        verify(rating, times(1)).handle(argThat(e -> e != null && e.eventId().equals("no-tariff")));
        assertThat(count("processed_events", account, "no-tariff")).isZero(); // rolled back
    }

    @Test
    void anUnreadableMessageIsDeadLetteredWithItsOriginalBytes() {
        String account = account();
        String meter = flatTariff();
        String garbage = "this is not json {{{ " + UUID.randomUUID();

        kafka.send(Topics.USAGE_EVENTS, 0, account, garbage);
        send(account, "good", meter);

        awaitCharge(account, "good");
        ConsumerRecord<String, String> dead = awaitDeadLetterContaining(garbage);
        assertThat(dead.value()).isEqualTo(garbage);
        assertThat(header(dead, KafkaHeaders.DLT_EXCEPTION_FQCN)).contains("Deserialization");
    }

    @Test
    void aTransientFailureIsRetriedUntilItSucceeds() {
        String account = account();
        String meter = flatTariff();
        doThrow(new TransientDataAccessResourceException("database blip"))
                .doThrow(new TransientDataAccessResourceException("database blip"))
                .doCallRealMethod()
                .when(rating).handle(argThat(e -> e != null && e.eventId().equals("flaky")));

        send(account, "flaky", meter);

        awaitCharge(account, "flaky");
        verify(rating, times(3)).handle(argThat(e -> e != null && e.eventId().equals("flaky")));
        assertThat(deadLettersFor("flaky")).isEmpty();
    }

    @Test
    void whenRetriesAreExhaustedTheEventIsDeadLetteredAndTheNextOneIsRated() {
        String account = account();
        String meter = flatTariff();
        doThrow(new TransientDataAccessResourceException("database is down"))
                .when(rating).handle(argThat(e -> e != null && e.eventId().equals("always-fails")));

        send(account, "always-fails", meter);
        send(account, "after", meter);

        ConsumerRecord<String, String> dead = awaitDeadLetter("always-fails");
        assertThat(causeOf(dead)).endsWith("TransientDataAccessResourceException");
        awaitCharge(account, "after");
        // one first attempt plus three retries
        verify(rating, times(4)).handle(argThat(e -> e != null && e.eventId().equals("always-fails")));
    }

    // ---- helpers ----

    private String account() {
        String id = "acc-" + UUID.randomUUID();
        jdbc.update("INSERT INTO accounts (id, balance) VALUES (?, 1000)", id);
        return id;
    }

    private String flatTariff() {
        String meter = "flat-" + UUID.randomUUID();
        jdbc.update("INSERT INTO tariffs (meter, model, effective_from, params) "
                + "VALUES (?, 'FLAT', '2026-01-01T00:00:00Z'::timestamptz, '{\"rate\":\"0.05\"}'::jsonb)", meter);
        return meter;
    }

    /** Always partition 0, so bad and good events share a partition and order. */
    private void send(String account, String eventId, String meter) {
        String body = """
                {"eventId":"%s","accountId":"%s","meter":"%s","quantity":1,"occurredAt":"2026-10-03T10:00:00Z"}"""
                .formatted(eventId, account, meter);
        kafka.send(Topics.USAGE_EVENTS, 0, account, body);
    }

    private void awaitCharge(String account, String eventId) {
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(count("charges", account, eventId)).isEqualTo(1));
    }

    private ConsumerRecord<String, String> awaitDeadLetter(String eventId) {
        return awaitDeadLetterContaining("\"" + eventId + "\"");
    }

    private ConsumerRecord<String, String> awaitDeadLetterContaining(String text) {
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> {
            found.clear();
            found.addAll(readDeadLetters().stream().filter(r -> r.value() != null && r.value().contains(text)).toList());
            assertThat(found).hasSize(1);
        });
        return found.get(0);
    }

    private List<ConsumerRecord<String, String>> deadLettersFor(String eventId) {
        return readDeadLetters().stream().filter(r -> r.value() != null && r.value().contains("\"" + eventId + "\"")).toList();
    }

    private static String causeOf(ConsumerRecord<String, String> record) {
        String cause = header(record, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN);
        return cause != null ? cause : header(record, KafkaHeaders.DLT_EXCEPTION_FQCN);
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header h = record.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }

    /** Reads the whole dead-letter topic with a throwaway consumer group. */
    private static List<ConsumerRecord<String, String>> readDeadLetters() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "dlq-test-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        List<ConsumerRecord<String, String>> out = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer =
                new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(Topics.USAGE_EVENTS_DLQ));
            long end = System.currentTimeMillis() + 3_000;
            while (System.currentTimeMillis() < end) {
                consumer.poll(Duration.ofMillis(300)).forEach(out::add);
            }
        }
        return out;
    }

    private int count(String table, String account, String eventId) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE account_id = ? AND event_id = ?",
                Integer.class, account, eventId);
    }
}
