package io.github.burakboduroglu.ratekit.ingest;

import io.github.burakboduroglu.ratekit.common.Topics;
import io.github.burakboduroglu.ratekit.common.UsageEvent;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/** Writes events to Kafka, keyed by account so one account stays in one partition, in order. */
@Component
class EventPublisher {

    private static final long SEND_TIMEOUT_SECONDS = 5;

    private final KafkaTemplate<String, UsageEvent> kafka;

    EventPublisher(KafkaTemplate<String, UsageEvent> kafka) {
        this.kafka = kafka;
    }

    /** Returns only after the broker acknowledged the write, so "accepted" means durably stored. */
    void publish(UsageEvent event) {
        try {
            kafka.send(Topics.USAGE_EVENTS, event.accountId(), event).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EventPublishException("interrupted while publishing event " + event.eventId(), e);
        } catch (ExecutionException | TimeoutException e) {
            throw new EventPublishException("could not publish event " + event.eventId(), e);
        }
    }
}
