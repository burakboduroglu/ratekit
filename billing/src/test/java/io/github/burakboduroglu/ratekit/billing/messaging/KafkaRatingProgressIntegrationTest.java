package io.github.burakboduroglu.ratekit.billing.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.burakboduroglu.ratekit.billing.exception.RatingProgressUnknownException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaAdmin;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The offset arithmetic against a real broker. Each test has its own topic and consumer group, and
 * writes records with explicit timestamps around a cutoff, then commits offsets as rating would.
 */
@Testcontainers
class KafkaRatingProgressIntegrationTest {

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:4.3.1"));

    private final Instant cutoff = Instant.parse("2026-10-01T01:00:00Z");

    @Test
    void caughtUpOnlyOnceEveryRecordWrittenBeforeTheCutoffIsCommitted() throws Exception {
        String topic = topic();
        long a = send(topic, 0, cutoff.minusSeconds(120));
        long b = send(topic, 0, cutoff.minusSeconds(60));
        send(topic, 0, cutoff.plusSeconds(60)); // after the cutoff: not needed for the month
        send(topic, 1, cutoff.minusSeconds(30));
        String group = "g-" + UUID.randomUUID();
        KafkaRatingProgress progress = progress(topic, group);

        assertThat(progress.caughtUpTo(cutoff)).as("nothing committed").isFalse();
        commit(group, topic, Map.of(0, a + 1, 1, 1L));
        assertThat(progress.caughtUpTo(cutoff)).as("b still unrated").isFalse();
        commit(group, topic, Map.of(0, b + 1, 1, 0L));
        assertThat(progress.caughtUpTo(cutoff)).as("partition 1 still unrated").isFalse();
        commit(group, topic, Map.of(0, b + 1, 1, 1L));
        assertThat(progress.caughtUpTo(cutoff)).as("everything before the cutoff rated").isTrue();
    }

    @Test
    void withNothingWrittenSinceTheCutoffTheWholePartitionMustBeRated() throws Exception {
        String topic = topic();
        long last = send(topic, 0, cutoff.minusSeconds(10));
        String group = "g-" + UUID.randomUUID();
        KafkaRatingProgress progress = progress(topic, group);

        commit(group, topic, Map.of(0, last));
        assertThat(progress.caughtUpTo(cutoff)).isFalse();
        commit(group, topic, Map.of(0, last + 1));
        assertThat(progress.caughtUpTo(cutoff)).isTrue();
    }

    @Test
    void anUnreachableBrokerIsReportedNotTakenAsCaughtUp() {
        KafkaRatingProgress progress = new KafkaRatingProgress(
                new KafkaAdmin(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:1",
                        AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 2000,
                        AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 1000)),
                "usage-events", "ratekit-rating", Duration.ofSeconds(3));

        assertThatThrownBy(() -> progress.caughtUpTo(cutoff)).isInstanceOf(RatingProgressUnknownException.class);
    }

    // ---- helpers ----

    private static KafkaRatingProgress progress(String topic, String group) {
        return new KafkaRatingProgress(
                new KafkaAdmin(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers())),
                topic, group, Duration.ofSeconds(10));
    }

    private static String topic() throws Exception {
        String topic = "usage-" + UUID.randomUUID();
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(topic, 2, (short) 1))).all().get();
        }
        return topic;
    }

    /** @return the offset the record was written at */
    private static long send(String topic, int partition, Instant timestamp) throws Exception {
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()),
                new StringSerializer(), new StringSerializer())) {
            return producer.send(new ProducerRecord<>(topic, partition, timestamp.toEpochMilli(), "acc", "{}")).get().offset();
        }
    }

    /** Commits offsets for the group the way rating's listener does after a transaction. */
    private static void commit(String group, String topic, Map<Integer, Long> offsets) {
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, group),
                new StringDeserializer(), new StringDeserializer())) {
            Map<TopicPartition, OffsetAndMetadata> commit = new java.util.HashMap<>();
            offsets.forEach((p, o) -> commit.put(new TopicPartition(topic, p), new OffsetAndMetadata(o)));
            consumer.commitSync(commit);
        }
    }
}
