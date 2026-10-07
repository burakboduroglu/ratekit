package io.github.burakboduroglu.ratekit.rating.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of {@code POST /v1/accounts}. */
public record CreateAccountRequest(
        @Schema(description = "Chosen by the caller; the same id usage events carry", example = "acc-42")
        @NotBlank @Size(max = 64) String accountId) {
}
