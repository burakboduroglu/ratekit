package io.github.burakboduroglu.ratekit.ingest.mapper;

import io.github.burakboduroglu.ratekit.common.UsageEvent;
import io.github.burakboduroglu.ratekit.ingest.dto.EventRequest;
import io.github.burakboduroglu.ratekit.ingest.dto.EventResponse;
import org.springframework.stereotype.Component;

/** Converts between the API shapes (DTOs) and the domain event, so neither knows about the other. */
@Component
public class EventMapper {

    public UsageEvent toEvent(EventRequest request) {
        return new UsageEvent(request.eventId(), request.accountId(), request.meter(),
                request.quantity(), request.occurredAt());
    }

    public EventResponse toResponse(UsageEvent event) {
        return new EventResponse(event.eventId(), event.accountId(), EventResponse.ACCEPTED);
    }
}
