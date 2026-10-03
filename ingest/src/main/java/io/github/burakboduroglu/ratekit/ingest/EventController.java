package io.github.burakboduroglu.ratekit.ingest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/events")
class EventController {

    private final EventPublisher publisher;

    EventController(EventPublisher publisher) {
        this.publisher = publisher;
    }

    @Operation(summary = "Submit a usage event",
            description = "Validates the event and writes it to Kafka. Rating happens asynchronously.")
    @ApiResponse(responseCode = "202", description = "Stored in Kafka; will be rated")
    @ApiResponse(responseCode = "400", description = "Invalid event")
    @ApiResponse(responseCode = "503", description = "Kafka did not acknowledge the write; retry")
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    void submit(@Valid @RequestBody EventRequest request) {
        publisher.publish(request.toEvent());
    }
}
