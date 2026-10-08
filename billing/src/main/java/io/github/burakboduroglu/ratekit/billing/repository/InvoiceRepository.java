package io.github.burakboduroglu.ratekit.billing.repository;

import io.github.burakboduroglu.ratekit.common.BillingPeriod;
import io.github.burakboduroglu.ratekit.common.Money;
import io.github.burakboduroglu.ratekit.billing.domain.AdjustmentLine;
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
     * Inserts the invoice header, with a total of zero until {@link #setTotal}, unless one already
     * exists for the account and period.
     *
     * @return the new invoice id, or empty if the invoice already existed (nothing was written)
     */
    public Optional<Long> insertHeaderIfAbsent(String accountId, BillingPeriod period) {
        return jdbc.sql("INSERT INTO invoices (account_id, period_start, period_end, total) "
                        + "VALUES (?, ?, ?, 0) ON CONFLICT (account_id, period_start) DO NOTHING RETURNING id")
                .params(accountId, utc(period.start()), utc(period.end()))
                .query(Long.class)
                .optional();
    }

    /** Writes the lines, the adjustments and the total of a header inserted in the same transaction. */
    public void complete(long invoiceId, Invoice invoice) {
        for (InvoiceLine line : invoice.lines()) {
            jdbc.sql("INSERT INTO invoice_lines (invoice_id, meter, quantity, amount) VALUES (?, ?, ?, ?)")
                    .params(invoiceId, line.meter(), line.quantity(), line.amount().amount())
                    .update();
        }
        for (AdjustmentLine adjustment : invoice.adjustments()) {
            jdbc.sql("INSERT INTO invoice_adjustments (invoice_id, original_period_start, meter, quantity, amount) "
                            + "VALUES (?, ?, ?, ?, ?)")
                    .params(invoiceId, utc(adjustment.originalPeriod().start()), adjustment.meter(),
                            adjustment.quantity(), adjustment.amount().amount())
                    .update();
        }
        jdbc.sql("UPDATE invoices SET total = ? WHERE id = ?").params(invoice.total().amount(), invoiceId).update();
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
        List<AdjustmentLine> adjustments = jdbc.sql("SELECT original_period_start, meter, quantity, amount "
                        + "FROM invoice_adjustments WHERE invoice_id = ? ORDER BY original_period_start, meter")
                .param(id.get())
                .query((rs, row) -> new AdjustmentLine(
                        BillingPeriod.containing(rs.getTimestamp("original_period_start").toInstant()),
                        rs.getString("meter"), rs.getLong("quantity"), new Money(rs.getBigDecimal("amount"))))
                .list();
        return Optional.of(Invoice.of(accountId, period, lines, adjustments));
    }

    private static OffsetDateTime utc(java.time.Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
