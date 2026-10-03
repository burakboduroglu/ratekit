package io.github.burakboduroglu.ratekit.ingest.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Body of the {@code 202} answer: which event was accepted. */
public record EventResponse(
        @Schema(description = "Echo of the accepted event id", example = "evt-0001") String eventId,
        @Schema(description = "Echo of the account", example = "acc-42") String accountId,
        @Schema(description = "Always ACCEPTED: the event is stored in Kafka and will be rated", example = "ACCEPTED") String status) {

    public static final String ACCEPTED = "ACCEPTED";
}
