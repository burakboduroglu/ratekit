package io.github.burakboduroglu.ratekit.rating.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Body of {@code POST /v1/accounts/{accountId}/top-ups}. The amount may have at most four decimals:
 * money coming in is never rounded silently.
 */
public record TopUpRequest(
        @Schema(description = "Chosen by the caller, unique per account; send the same id to retry safely", example = "tu-2026-10-07-1")
        @NotBlank @Size(max = 64) String topUpId,
        @Schema(description = "Positive, at most 4 decimals; a string avoids floating point", example = "10.00", type = "string")
        @NotNull @Positive @Digits(integer = 15, fraction = 4) BigDecimal amount) {
}
