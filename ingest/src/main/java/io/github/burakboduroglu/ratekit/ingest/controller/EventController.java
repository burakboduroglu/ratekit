package io.github.burakboduroglu.ratekit.ingest.controller;

import io.github.burakboduroglu.ratekit.common.UsageEvent;
import io.github.burakboduroglu.ratekit.ingest.dto.EventRequest;
import io.github.burakboduroglu.ratekit.ingest.dto.EventResponse;
import io.github.burakboduroglu.ratekit.ingest.mapper.EventMapper;
import io.github.burakboduroglu.ratekit.ingest.service.EventIngestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** HTTP entry point: translates the request, delegates to the service, translates the answer. */
@RestController
@RequestMapping("/v1/events")
public class EventController {

    private final EventIngestService service;
    private final EventMapper mapper;

    public EventController(EventIngestService service, EventMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @Operation(summary = "Submit a usage event",
            description = "Validates the event and writes it to Kafka. Rating happens asynchronously.")
    @ApiResponse(responseCode = "202", description = "Stored in Kafka; will be rated")
    @ApiResponse(responseCode = "400", description = "Invalid event")
    @ApiResponse(responseCode = "503", description = "Kafka did not acknowledge the write; retry")
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public EventResponse submit(@Valid @RequestBody EventRequest request) {
        UsageEvent event = mapper.toEvent(request);
        service.accept(event);
        return mapper.toResponse(event);
    }
}
