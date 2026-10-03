package io.github.burakboduroglu.ratekit.billing.controller;

import io.github.burakboduroglu.ratekit.billing.dto.InvoiceRunRequest;
import io.github.burakboduroglu.ratekit.billing.dto.InvoiceRunResponse;
import io.github.burakboduroglu.ratekit.billing.mapper.BillingPeriodMapper;
import io.github.burakboduroglu.ratekit.billing.mapper.InvoiceMapper;
import io.github.burakboduroglu.ratekit.billing.service.InvoiceRunService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/invoice-runs")
public class InvoiceRunController {

    private final InvoiceRunService runs;
    private final BillingPeriodMapper periods;
    private final InvoiceMapper mapper;

    public InvoiceRunController(InvoiceRunService runs, BillingPeriodMapper periods, InvoiceMapper mapper) {
        this.runs = runs;
        this.periods = periods;
        this.mapper = mapper;
    }

    @Operation(summary = "Invoice a finished month",
            description = "Creates one invoice per account that used something in the month. Safe to repeat: "
                    + "accounts that already have an invoice for the month are skipped.")
    @ApiResponse(responseCode = "200", description = "Run finished; the body says how many invoices were created")
    @ApiResponse(responseCode = "400", description = "The period is not a valid year and month")
    @ApiResponse(responseCode = "409", description = "The month has not ended yet")
    @PostMapping
    public InvoiceRunResponse run(@Valid @RequestBody InvoiceRunRequest request) {
        return mapper.toResponse(runs.run(periods.parse(request.period())));
    }
}
