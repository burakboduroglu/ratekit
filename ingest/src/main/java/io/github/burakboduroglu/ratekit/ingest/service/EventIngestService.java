package io.github.burakboduroglu.ratekit.ingest.service;

import io.github.burakboduroglu.ratekit.common.UsageEvent;
import io.github.burakboduroglu.ratekit.ingest.messaging.EventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Use case: accept a usage event. The place for ingest rules; today it hands the event to Kafka. */
@Service
public class EventIngestService {

    private static final Logger log = LoggerFactory.getLogger(EventIngestService.class);

    private final EventPublisher publisher;

    public EventIngestService(EventPublisher publisher) {
        this.publisher = publisher;
    }

    public void accept(UsageEvent event) {
        publisher.publish(event);
        log.debug("event accepted: account={} event={}", event.accountId(), event.eventId());
    }
}
