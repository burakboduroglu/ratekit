package io.github.burakboduroglu.ratekit.rating.dto;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/** Body of {@code POST /v1/tariffs}. The shape of {@code params} depends on the model. */
public record TariffRequest(
        @Schema(example = "sms") @NotBlank @Size(max = 64) String meter,
        @Schema(description = "FLAT, TIERED or FREE_QUOTA_THEN_FLAT", example = "FREE_QUOTA_THEN_FLAT") @NotBlank String model,
        @Schema(description = "When the version starts (ISO-8601, not in the past); omit for now", example = "2026-11-01T00:00:00Z") Instant effectiveFrom,
        @Schema(description = "FLAT {\"rate\":\"0.05\"}; FREE_QUOTA_THEN_FLAT {\"freeUnits\":100,\"rate\":\"0.05\"}; "
                + "TIERED {\"tiers\":[{\"upTo\":100,\"rate\":\"0.10\"},{\"upTo\":null,\"rate\":\"0.05\"}]}")
        @NotNull JsonNode params) {
}
