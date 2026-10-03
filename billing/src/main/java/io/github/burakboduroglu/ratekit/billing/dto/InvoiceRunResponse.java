package io.github.burakboduroglu.ratekit.billing.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record InvoiceRunResponse(
        @Schema(example = "2026-09") String period,
        @Schema(description = "Invoices written by this run") int invoicesCreated,
        @Schema(description = "Accounts that already had an invoice for the period and were skipped") int alreadyInvoiced) {
}
