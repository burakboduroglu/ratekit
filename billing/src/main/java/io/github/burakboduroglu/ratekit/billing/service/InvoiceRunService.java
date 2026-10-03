package io.github.burakboduroglu.ratekit.billing.service;

import io.github.burakboduroglu.ratekit.common.BillingPeriod;
import io.github.burakboduroglu.ratekit.billing.config.BillingProperties;
import io.github.burakboduroglu.ratekit.billing.domain.Invoice;
import io.github.burakboduroglu.ratekit.billing.domain.InvoiceLine;
import io.github.burakboduroglu.ratekit.billing.exception.PeriodNotClosedException;
import io.github.burakboduroglu.ratekit.billing.repository.ChargeUsageRepository;
import io.github.burakboduroglu.ratekit.billing.repository.UsageRow;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Invoices every account that used something in a finished period.
 *
 * <p>Safe to run again for the same period: the unique (account, period) key makes a second
 * attempt skip accounts that already have an invoice, and each invoice is its own transaction, so a
 * failure part-way leaves earlier invoices intact and a rerun finishes the rest. Accounts are
 * processed in batches by account id, so memory use does not grow with the number of accounts.
 */
@Service
public class InvoiceRunService {

    /** Outcome of a run. */
    public record RunSummary(BillingPeriod period, int created, int alreadyInvoiced) {
    }

    private static final Logger log = LoggerFactory.getLogger(InvoiceRunService.class);

    private final ChargeUsageRepository usage;
    private final InvoiceService invoices;
    private final BillingProperties properties;
    private final Clock clock;
    private final Counter created;
    private final Counter skipped;

    public InvoiceRunService(ChargeUsageRepository usage, InvoiceService invoices, BillingProperties properties,
                             Clock clock, MeterRegistry meters) {
        this.usage = usage;
        this.invoices = invoices;
        this.properties = properties;
        this.clock = clock;
        this.created = Counter.builder("ratekit.invoices").description("Invoice run results").tag("result", "created").register(meters);
        this.skipped = Counter.builder("ratekit.invoices").description("Invoice run results").tag("result", "already_invoiced").register(meters);
    }

    public RunSummary run(BillingPeriod period) {
        if (period.end().isAfter(clock.instant())) {
            throw new PeriodNotClosedException(period);
        }
        int createdNow = 0;
        int alreadyInvoiced = 0;
        String after = "";
        while (true) {
            List<String> accounts = usage.accountsWithCharges(period, after, properties.batchSize());
            if (accounts.isEmpty()) {
                break;
            }
            Map<String, List<InvoiceLine>> linesByAccount = linesByAccount(usage.usageFor(period, accounts));
            for (String account : accounts) {
                Invoice invoice = Invoice.of(account, period, linesByAccount.get(account));
                if (invoices.create(invoice)) {
                    createdNow++;
                } else {
                    alreadyInvoiced++;
                }
            }
            after = accounts.get(accounts.size() - 1);
        }
        created.increment(createdNow);
        skipped.increment(alreadyInvoiced);
        log.info("invoice run for {}: {} created, {} already invoiced", period.month(), createdNow, alreadyInvoiced);
        return new RunSummary(period, createdNow, alreadyInvoiced);
    }

    private static Map<String, List<InvoiceLine>> linesByAccount(List<UsageRow> rows) {
        Map<String, List<InvoiceLine>> result = new LinkedHashMap<>();
        for (UsageRow row : rows) {
            result.computeIfAbsent(row.accountId(), k -> new ArrayList<>())
                    .add(new InvoiceLine(row.meter(), row.quantity(), row.amount()));
        }
        return result;
    }
}
