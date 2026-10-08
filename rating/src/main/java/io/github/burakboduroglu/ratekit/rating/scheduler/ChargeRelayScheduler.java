package io.github.burakboduroglu.ratekit.rating.scheduler;

import io.github.burakboduroglu.ratekit.rating.service.ChargeRelayService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls the charge outbox. On by default; {@code ratekit.rating.charge-feed.enabled=false} turns it off
 * for running rating without Kafka. A failed pass leaves the rows unsent and the next pass retries.
 */
@Component
@ConditionalOnProperty(prefix = "ratekit.rating.charge-feed", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ChargeRelayScheduler {

    private static final Logger log = LoggerFactory.getLogger(ChargeRelayScheduler.class);

    private final ChargeRelayService relay;

    public ChargeRelayScheduler(ChargeRelayService relay) {
        this.relay = relay;
    }

    @Scheduled(fixedDelayString = "${ratekit.rating.charge-feed.poll-interval:PT0.5S}")
    void relay() {
        try {
            relay.relayPending();
        } catch (RuntimeException e) {
            log.warn("charge relay pass failed, will retry: {}", e.getMessage());
        }
    }
}
