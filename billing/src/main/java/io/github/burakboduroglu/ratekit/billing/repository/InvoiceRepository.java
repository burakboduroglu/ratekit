package io.github.burakboduroglu.ratekit.billing.repository;

import io.github.burakboduroglu.ratekit.common.BillingPeriod;
import io.github.burakboduroglu.ratekit.common.Money;
import io.github.burakboduroglu.ratekit.billing.domain.Invoice;
import io.github.burakboduroglu.ratekit.billing.domain.InvoiceLine;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class InvoiceRepository {

    private final JdbcClient jdbc;

    InvoiceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts the invoice header unless one already exists for the account and period.
     *
     * @return the new invoice id, or empty if the invoice already existed (nothing was written)
     */
    public Optional<Long> insertHeaderIfAbsent(Invoice invoice) {
        return jdbc.sql("INSERT INTO invoices (account_id, period_start, period_end, total) "
                        + "VALUES (?, ?, ?, ?) ON CONFLICT (account_id, period_start) DO NOTHING RETURNING id")
                .params(invoice.accountId(), utc(invoice.period().start()), utc(invoice.period().end()),
                        invoice.total().amount())
                .query(Long.class)
                .optional();
    }

    public void insertLines(long invoiceId, List<InvoiceLine> lines) {
        for (InvoiceLine line : lines) {
            jdbc.sql("INSERT INTO invoice_lines (invoice_id, meter, quantity, amount) VALUES (?, ?, ?, ?)")
                    .params(invoiceId, line.meter(), line.quantity(), line.amount().amount())
                    .update();
        }
    }

    public Optional<Invoice> find(String accountId, BillingPeriod period) {
        Optional<Long> id = jdbc.sql("SELECT id FROM invoices WHERE account_id = ? AND period_start = ?")
                .params(accountId, utc(period.start()))
                .query(Long.class)
                .optional();
        if (id.isEmpty()) {
            return Optional.empty();
        }
        List<InvoiceLine> lines = jdbc.sql("SELECT meter, quantity, amount FROM invoice_lines "
                        + "WHERE invoice_id = ? ORDER BY meter")
                .param(id.get())
                .query((rs, row) -> new InvoiceLine(rs.getString("meter"), rs.getLong("quantity"),
                        new Money(rs.getBigDecimal("amount"))))
                .list();
        return Optional.of(Invoice.of(accountId, period, lines));
    }

    private static OffsetDateTime utc(java.time.Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
