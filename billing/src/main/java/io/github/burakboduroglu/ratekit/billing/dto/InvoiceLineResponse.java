package io.github.burakboduroglu.ratekit.billing.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record InvoiceLineResponse(
        @Schema(example = "sms") String meter,
        @Schema(description = "Total units of the meter in the period", example = "105") long quantity,
        @Schema(description = "Amount as a decimal string with 4 digits, to avoid float rounding", example = "0.2500") String amount) {
}
