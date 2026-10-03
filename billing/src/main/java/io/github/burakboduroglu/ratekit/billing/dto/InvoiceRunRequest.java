package io.github.burakboduroglu.ratekit.billing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/** Body of {@code POST /v1/invoice-runs}. */
public record InvoiceRunRequest(
        @Schema(description = "Calendar month to invoice, in UTC. The month must have ended.", example = "2026-09")
        @NotBlank String period) {
}
