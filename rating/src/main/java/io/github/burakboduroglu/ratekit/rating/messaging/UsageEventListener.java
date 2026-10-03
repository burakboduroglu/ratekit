package io.github.burakboduroglu.ratekit.rating.messaging;

import io.github.burakboduroglu.ratekit.common.Topics;
import io.github.burakboduroglu.ratekit.common.UsageEvent;
import io.github.burakboduroglu.ratekit.rating.service.RatingService;
import io.github.burakboduroglu.ratekit.rating.service.RatingService.Outcome;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Kafka entry point: hands each event to the rating service. Offsets are committed after it returns. */
@Component
class UsageEventListener {

    private final RatingService rating;
    private final Timer duration;
    private final Map<Outcome, Counter> processed = new EnumMap<>(Outcome.class);

    UsageEventListener(RatingService rating, MeterRegistry meters) {
        this.rating = rating;
        this.duration = Timer.builder("ratekit.rating.duration")
                .description("Time to rate one event, including the database commit")
                .publishPercentileHistogram()
                .register(meters);
        for (Outcome outcome : Outcome.values()) {
            processed.put(outcome, Counter.builder("ratekit.events.processed")
                    .description("Usage events handled by rating, by outcome")
                    .tag("outcome", outcome.name().toLowerCase())
                    .register(meters));
        }
    }

    @KafkaListener(topics = Topics.USAGE_EVENTS)
    void onEvent(UsageEvent event) {
        // measured here, outside the service's transaction, so the commit is included
        Outcome outcome = duration.record(() -> rating.handle(event));
        processed.get(outcome).increment();
    }
}
