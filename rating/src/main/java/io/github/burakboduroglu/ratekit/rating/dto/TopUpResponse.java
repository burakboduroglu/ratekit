package io.github.burakboduroglu.ratekit.rating.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record TopUpResponse(
        @Schema(example = "acc-42") String accountId,
        @Schema(example = "tu-2026-10-07-1") String topUpId,
        @Schema(example = "10.0000") String amount,
        @Schema(description = "Balance after the top-up", example = "10.0000") String balance) {
}
