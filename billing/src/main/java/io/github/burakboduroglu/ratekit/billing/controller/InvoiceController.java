package io.github.burakboduroglu.ratekit.billing.controller;

import io.github.burakboduroglu.ratekit.billing.dto.InvoiceResponse;
import io.github.burakboduroglu.ratekit.billing.exception.InvoiceNotFoundException;
import io.github.burakboduroglu.ratekit.billing.mapper.BillingPeriodMapper;
import io.github.burakboduroglu.ratekit.billing.mapper.InvoiceMapper;
import io.github.burakboduroglu.ratekit.billing.service.InvoiceService;
import io.github.burakboduroglu.ratekit.common.BillingPeriod;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/invoices")
public class InvoiceController {

    private final InvoiceService invoices;
    private final BillingPeriodMapper periods;
    private final InvoiceMapper mapper;

    public InvoiceController(InvoiceService invoices, BillingPeriodMapper periods, InvoiceMapper mapper) {
        this.invoices = invoices;
        this.periods = periods;
        this.mapper = mapper;
    }

    @Operation(summary = "Read an account's invoice for a month")
    @ApiResponse(responseCode = "200", description = "The invoice with its lines and total")
    @ApiResponse(responseCode = "404", description = "No invoice for that account and month")
    @GetMapping("/{accountId}")
    public InvoiceResponse get(@PathVariable String accountId, @RequestParam String period) {
        BillingPeriod billingPeriod = periods.parse(period);
        return invoices.find(accountId, billingPeriod)
                .map(mapper::toResponse)
                .orElseThrow(() -> new InvoiceNotFoundException(accountId, billingPeriod));
    }
}
