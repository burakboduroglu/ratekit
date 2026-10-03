package io.github.burakboduroglu.ratekit.ingest.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;

/** Request body of {@code POST /v1/events}. Validated at the API boundary. */
public record EventRequest(
        @Schema(description = "Unique per account; the idempotency key", example = "evt-0001") @NotBlank String eventId,
        @Schema(description = "Account to charge", example = "acc-42") @NotBlank String accountId,
        @Schema(description = "What was used", example = "sms") @NotBlank String meter,
        @Schema(description = "Amount used in the meter's smallest whole unit", example = "1") @Positive long quantity,
        @Schema(description = "When the usage happened (ISO-8601)", example = "2026-10-03T10:00:00Z") @NotNull Instant occurredAt) {
}
