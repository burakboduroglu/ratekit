package io.github.burakboduroglu.ratekit.rating.messaging;

import io.github.burakboduroglu.ratekit.common.ChargeEvent;
import io.github.burakboduroglu.ratekit.common.ChargeFeedWatermark;
import io.github.burakboduroglu.ratekit.common.Topics;
import io.github.burakboduroglu.ratekit.rating.config.ChargeFeedProperties;
import io.github.burakboduroglu.ratekit.rating.exception.ChargeFeedPublishException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

/**
 * Writes to the {@code charges} topic and returns only once Kafka has acknowledged every record, so
 * the caller may mark them sent. If it throws, some records may still have been written; they are
 * sent again later and billing stores each charge once (at-least-once, ADR 0019).
 */
@Component
public class ChargeFeedPublisher {

    private final KafkaTemplate<String, Object> kafka;
    private final Duration timeout;

    public ChargeFeedPublisher(ChargeFeedProducer producer, ChargeFeedProperties properties) {
        this.kafka = producer.template();
        this.timeout = properties.sendTimeout();
    }

    /**
     * Keyed by account, so one account's charges share a partition and keep their order. The record
     * timestamp is when rating stored the charge.
     */
    public void publish(List<ChargeEvent> charges) {
        List<CompletableFuture<SendResult<String, Object>>> sends = new ArrayList<>();
        for (ChargeEvent charge : charges) {
            sends.add(kafka.send(new ProducerRecord<>(Topics.CHARGES, null, charge.ratedAt().toEpochMilli(),
                    charge.accountId(), charge)));
        }
        awaitAll(sends);
    }

    /** One marker on every partition: each partition is read on its own, so each needs its own marker. */
    public void publishWatermark(Instant publishedThrough) {
        int partitions = kafka.partitionsFor(Topics.CHARGES).size();
        ChargeFeedWatermark watermark = new ChargeFeedWatermark(publishedThrough, partitions);
        List<CompletableFuture<SendResult<String, Object>>> sends = new ArrayList<>();
        for (int partition = 0; partition < partitions; partition++) {
            sends.add(kafka.send(new ProducerRecord<>(Topics.CHARGES, partition, null, watermark)));
        }
        awaitAll(sends);
    }

    private void awaitAll(List<CompletableFuture<SendResult<String, Object>>> sends) {
        try {
            CompletableFuture.allOf(sends.toArray(CompletableFuture[]::new)).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ChargeFeedPublishException(e);
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            throw new ChargeFeedPublishException(e);
        }
    }
}
