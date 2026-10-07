package io.github.burakboduroglu.ratekit.rating.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record AccountResponse(
        @Schema(example = "acc-42") String accountId,
        @Schema(description = "What the account can still spend, a decimal string with 4 digits", example = "10.0000") String balance) {
}
