package io.github.burakboduroglu.ratekit.ingest.service;

import io.github.burakboduroglu.ratekit.common.UsageEvent;
import io.github.burakboduroglu.ratekit.ingest.messaging.EventPublisher;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Use case: accept a usage event. The place for ingest rules; today it hands the event to Kafka. */
@Service
public class EventIngestService {

    private static final Logger log = LoggerFactory.getLogger(EventIngestService.class);

    private final EventPublisher publisher;
    private final Counter accepted;

    public EventIngestService(EventPublisher publisher, MeterRegistry meters) {
        this.publisher = publisher;
        this.accepted = Counter.builder("ratekit.events.accepted")
                .description("Usage events written to Kafka and acknowledged by the broker")
                .register(meters);
    }

    public void accept(UsageEvent event) {
        publisher.publish(event);
        accepted.increment();
        log.debug("event accepted: account={} event={}", event.accountId(), event.eventId());
    }
}
