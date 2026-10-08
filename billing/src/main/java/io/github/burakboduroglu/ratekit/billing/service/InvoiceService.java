package io.github.burakboduroglu.ratekit.billing.service;

import io.github.burakboduroglu.ratekit.billing.domain.AdjustmentLine;
import io.github.burakboduroglu.ratekit.billing.domain.Invoice;
import io.github.burakboduroglu.ratekit.billing.domain.InvoiceLine;
import io.github.burakboduroglu.ratekit.billing.repository.ChargeUsageRepository;
import io.github.burakboduroglu.ratekit.billing.repository.InvoiceRepository;
import io.github.burakboduroglu.ratekit.billing.repository.UsageRow;
import io.github.burakboduroglu.ratekit.common.BillingPeriod;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

/** Writes and reads single invoices. Each creation is its own transaction. */
@Service
public class InvoiceService {

    /** What happened to one account in a run. */
    public enum Outcome { CREATED, ALREADY_INVOICED, NOTHING_TO_BILL }

    private final InvoiceRepository invoices;
    private final ChargeUsageRepository usage;

    public InvoiceService(InvoiceRepository invoices, ChargeUsageRepository usage) {
        this.invoices = invoices;
        this.usage = usage;
    }

    /**
     * Invoices the account for the period in one transaction, unless it already has an invoice for it:
     * the header is inserted first (the unique key decides who writes it), then the charges it bills
     * are claimed (ADR 0020). Charges of the period become lines; late charges of earlier, already
     * invoiced months become adjustment lines naming their month.
     */
    @Transactional
    public Outcome create(String accountId, BillingPeriod period) {
        Optional<Long> id = invoices.insertHeaderIfAbsent(accountId, period);
        if (id.isEmpty()) {
            return Outcome.ALREADY_INVOICED;
        }
        List<UsageRow> claimed = usage.claim(id.get(), accountId, period);
        if (claimed.isEmpty()) {
            // another invoice claimed the late charges this account was listed for; write nothing
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return Outcome.NOTHING_TO_BILL;
        }
        List<InvoiceLine> lines = new ArrayList<>();
        List<AdjustmentLine> adjustments = new ArrayList<>();
        for (UsageRow row : claimed) {
            if (row.period().equals(period)) {
                lines.add(new InvoiceLine(row.meter(), row.quantity(), row.amount()));
            } else {
                adjustments.add(new AdjustmentLine(row.period(), row.meter(), row.quantity(), row.amount()));
            }
        }
        invoices.complete(id.get(), Invoice.of(accountId, period, lines, adjustments));
        return Outcome.CREATED;
    }

    @Transactional(readOnly = true)
    public Optional<Invoice> find(String accountId, BillingPeriod period) {
        return invoices.find(accountId, period);
    }
}
