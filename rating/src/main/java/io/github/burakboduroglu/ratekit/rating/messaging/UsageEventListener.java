package io.github.burakboduroglu.ratekit.rating.messaging;

import io.github.burakboduroglu.ratekit.common.Topics;
import io.github.burakboduroglu.ratekit.common.UsageEvent;
import io.github.burakboduroglu.ratekit.rating.RatingService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Kafka entry point: hands each event to the rating service. Offsets are committed after it returns. */
@Component
class UsageEventListener {

    private final RatingService rating;

    UsageEventListener(RatingService rating) {
        this.rating = rating;
    }

    @KafkaListener(topics = Topics.USAGE_EVENTS)
    void onEvent(UsageEvent event) {
        rating.handle(event);
    }
}
