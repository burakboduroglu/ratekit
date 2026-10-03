package io.github.burakboduroglu.ratekit.ingest.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.burakboduroglu.ratekit.common.UsageEvent;
import io.github.burakboduroglu.ratekit.ingest.dto.EventRequest;
import io.github.burakboduroglu.ratekit.ingest.dto.EventResponse;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class EventMapperTest {

    private final EventMapper mapper = new EventMapper();
    private final Instant at = Instant.parse("2026-10-03T10:00:00Z");

    @Test
    void mapsEveryRequestFieldToTheDomainEvent() {
        UsageEvent event = mapper.toEvent(new EventRequest("e1", "acc-1", "sms", 3, at));

        assertThat(event).isEqualTo(new UsageEvent("e1", "acc-1", "sms", 3, at));
    }

    @Test
    void theResponseEchoesTheEventAndMarksItAccepted() {
        EventResponse response = mapper.toResponse(new UsageEvent("e1", "acc-1", "sms", 3, at));

        assertThat(response).isEqualTo(new EventResponse("e1", "acc-1", "ACCEPTED"));
    }
}
