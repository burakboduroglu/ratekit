package io.github.burakboduroglu.ratekit.billing.service;

import io.github.burakboduroglu.ratekit.common.BillingPeriod;
import io.github.burakboduroglu.ratekit.billing.domain.Invoice;
import io.github.burakboduroglu.ratekit.billing.repository.InvoiceRepository;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Stores and reads single invoices. Each creation is its own transaction. */
@Service
public class InvoiceService {

    private final InvoiceRepository invoices;

    public InvoiceService(InvoiceRepository invoices) {
        this.invoices = invoices;
    }

    /**
     * Writes the invoice and its lines in one transaction, unless the account was already invoiced
     * for the period.
     *
     * @return true if the invoice was created, false if it already existed
     */
    @Transactional
    public boolean create(Invoice invoice) {
        Optional<Long> id = invoices.insertHeaderIfAbsent(invoice);
        if (id.isEmpty()) {
            return false;
        }
        invoices.insertLines(id.get(), invoice.lines());
        return true;
    }

    @Transactional(readOnly = true)
    public Optional<Invoice> find(String accountId, BillingPeriod period) {
        return invoices.find(accountId, period);
    }
}
