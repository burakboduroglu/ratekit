package io.github.burakboduroglu.ratekit.billing.messaging;

import io.github.burakboduroglu.ratekit.billing.config.BillingProperties;
import io.github.burakboduroglu.ratekit.billing.exception.RatingProgressUnknownException;
import io.github.burakboduroglu.ratekit.billing.service.RatingProgress;
import io.github.burakboduroglu.ratekit.common.Topics;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

/**
 * Asks Kafka whether rating's consumer group is past every usage event written before a cutoff.
 *
 * <p>For each partition of the usage topic: the first offset whose record timestamp is at or after
 * the cutoff marks where "before the cutoff" ends (if no such record exists yet, the end of the
 * partition does). Rating commits an offset only after the database transaction for the records
 * before it committed, so a committed offset at or past that mark means every earlier event is
 * either a charge, a rejection or a dead letter. Lag on its own would not do: with steady traffic it
 * is never zero.
 */
@Component
@ConditionalOnProperty(prefix = "ratekit.billing.rating-progress", name = "enabled", havingValue = "true", matchIfMissing = true)
public class KafkaRatingProgress implements RatingProgress {

    private static final Logger log = LoggerFactory.getLogger(KafkaRatingProgress.class);

    private final KafkaAdmin kafka;
    private final String topic;
    private final String consumerGroup;
    private final Duration timeout;

    @Autowired
    public KafkaRatingProgress(KafkaAdmin kafka, BillingProperties properties) {
        this(kafka, Topics.USAGE_EVENTS, properties.ratingProgress().consumerGroup(), properties.ratingProgress().timeout());
    }

    /** For tests that need a topic of their own. */
    KafkaRatingProgress(KafkaAdmin kafka, String topic, String consumerGroup, Duration timeout) {
        this.kafka = kafka;
        this.topic = topic;
        this.consumerGroup = consumerGroup;
        this.timeout = timeout;
    }

    @Override
    public boolean caughtUpTo(Instant cutoff) {
        try (Admin admin = Admin.create(kafka.getConfigurationProperties())) {
            List<TopicPartition> partitions = admin.describeTopics(List.of(topic)).allTopicNames()
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS).get(topic).partitions().stream()
                    .map(p -> new TopicPartition(topic, p.partition()))
                    .toList();
            Map<TopicPartition, Long> required = required(admin, partitions, cutoff);
            Map<TopicPartition, OffsetAndMetadata> committed = admin.listConsumerGroupOffsets(consumerGroup)
                    .partitionsToOffsetAndMetadata().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            Map<TopicPartition, Long> earliest = offsets(admin, partitions, OffsetSpec.earliest());
            for (TopicPartition partition : partitions) {
                OffsetAndMetadata done = committed.get(partition);
                // a partition the group never committed on has been read up to its first record at most
                long position = done != null ? done.offset() : earliest.get(partition);
                if (position < required.get(partition)) {
                    log.info("rating is behind on {}: committed {}, needs {} for cutoff {}",
                            partition, position, required.get(partition), cutoff);
                    return false;
                }
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RatingProgressUnknownException(e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof UnknownTopicOrPartitionException) {
                return true; // the topic was never created, so no usage was ever written
            }
            throw new RatingProgressUnknownException(e);
        } catch (TimeoutException | RuntimeException e) {
            throw new RatingProgressUnknownException(e);
        }
    }

    /** Per partition, the offset rating must have reached: the first record at or after the cutoff, or the end. */
    private Map<TopicPartition, Long> required(Admin admin, List<TopicPartition> partitions, Instant cutoff)
            throws ExecutionException, InterruptedException, TimeoutException {
        Map<TopicPartition, Long> atCutoff = offsets(admin, partitions, OffsetSpec.forTimestamp(cutoff.toEpochMilli()));
        Map<TopicPartition, Long> end = offsets(admin, partitions, OffsetSpec.latest());
        Map<TopicPartition, Long> result = new HashMap<>();
        for (TopicPartition partition : partitions) {
            long offset = atCutoff.get(partition);
            result.put(partition, offset >= 0 ? offset : end.get(partition)); // -1: nothing written since the cutoff
        }
        return result;
    }

    private Map<TopicPartition, Long> offsets(Admin admin, List<TopicPartition> partitions, OffsetSpec spec)
            throws ExecutionException, InterruptedException, TimeoutException {
        Map<TopicPartition, OffsetSpec> request = new HashMap<>();
        partitions.forEach(p -> request.put(p, spec));
        Map<TopicPartition, Long> result = new HashMap<>();
        admin.listOffsets(request).all().get(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .forEach((partition, info) -> result.put(partition, info.offset()));
        return result;
    }
}
