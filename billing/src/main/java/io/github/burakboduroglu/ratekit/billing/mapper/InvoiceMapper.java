package io.github.burakboduroglu.ratekit.billing.mapper;

import io.github.burakboduroglu.ratekit.billing.domain.Invoice;
import io.github.burakboduroglu.ratekit.billing.dto.AdjustmentLineResponse;
import io.github.burakboduroglu.ratekit.billing.dto.InvoiceLineResponse;
import io.github.burakboduroglu.ratekit.billing.dto.InvoiceResponse;
import io.github.burakboduroglu.ratekit.billing.dto.InvoiceRunResponse;
import io.github.burakboduroglu.ratekit.billing.service.InvoiceRunService.RunSummary;
import org.springframework.stereotype.Component;

@Component
public class InvoiceMapper {

    private final BillingPeriodMapper periods;

    public InvoiceMapper(BillingPeriodMapper periods) {
        this.periods = periods;
    }

    public InvoiceResponse toResponse(Invoice invoice) {
        return new InvoiceResponse(
                invoice.accountId(),
                periods.format(invoice.period()),
                invoice.lines().stream()
                        .map(l -> new InvoiceLineResponse(l.meter(), l.quantity(), l.amount().toString()))
                        .toList(),
                invoice.adjustments().stream()
                        .map(a -> new AdjustmentLineResponse(periods.format(a.originalPeriod()), a.meter(), a.quantity(),
                                a.amount().toString()))
                        .toList(),
                invoice.total().toString());
    }

    public InvoiceRunResponse toResponse(RunSummary summary) {
        return new InvoiceRunResponse(periods.format(summary.period()), summary.created(), summary.alreadyInvoiced());
    }
}
