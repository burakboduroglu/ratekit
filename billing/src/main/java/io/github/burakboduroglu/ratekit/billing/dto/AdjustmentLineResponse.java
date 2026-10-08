package io.github.burakboduroglu.ratekit.billing.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record AdjustmentLineResponse(
        @Schema(description = "The earlier, already invoiced month the late usage belongs to", example = "2026-08")
        String originalPeriod,
        @Schema(example = "sms") String meter,
        @Schema(description = "Late units of the meter in that month", example = "5") long quantity,
        @Schema(description = "Amount as a decimal string with 4 digits, to avoid float rounding", example = "0.2500") String amount) {
}
