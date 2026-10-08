package io.github.burakboduroglu.ratekit.billing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record InvoiceResponse(
        @Schema(example = "acc-demo") String accountId,
        @Schema(example = "2026-09") String period,
        List<InvoiceLineResponse> lines,
        @Schema(description = "Usage of earlier, already invoiced months that arrived late, billed here (ADR 0020)")
        List<AdjustmentLineResponse> adjustments,
        @Schema(description = "Sum of the lines and the adjustments, a decimal string with 4 digits", example = "0.7500") String total) {
}
