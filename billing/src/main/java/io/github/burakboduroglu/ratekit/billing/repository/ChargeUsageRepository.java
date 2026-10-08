package io.github.burakboduroglu.ratekit.billing.repository;

import io.github.burakboduroglu.ratekit.common.BillingPeriod;
import io.github.burakboduroglu.ratekit.common.Money;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads billing's own copy of the charges, which the charge feed fills (ADR 0019), and claims them
 * for invoices (ADR 0020).
 */
@Repository
public class ChargeUsageRepository {

    /** The calendar month (UTC) a charge belongs to, as the instant it starts. */
    private static final String MONTH_OF_CHARGE = "date_trunc('month', occurred_at AT TIME ZONE 'UTC') AT TIME ZONE 'UTC'";

    /**
     * Charges an invoice for the period bills: those of the period, and late ones, still unbilled, of
     * an earlier month whose run has completed. A charge of an earlier month never run is not late; it
     * waits for that month's own run.
     */
    private static final String BILLABLE = "occurred_at < :end AND (occurred_at >= :start OR (invoice_id IS NULL AND "
            + MONTH_OF_CHARGE + " IN (SELECT period_start FROM invoiced_periods)))";

    private final JdbcClient jdbc;

    ChargeUsageRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The next batch of accounts that used something in the period or have late charges to bill, in
     * account order, starting after {@code afterAccountId} (keyset paging: stable even while the table
     * grows).
     */
    public List<String> accountsToInvoice(BillingPeriod period, String afterAccountId, int limit) {
        return jdbc.sql("SELECT DISTINCT account_id FROM charges WHERE account_id > :after AND " + BILLABLE
                        + " ORDER BY account_id LIMIT :limit")
                .param("start", utc(period.start()))
                .param("end", utc(period.end()))
                .param("after", afterAccountId)
                .param("limit", limit)
                .query(String.class)
                .list();
    }

    /**
     * Marks the account's unbilled charges that an invoice for the period bills as billed by
     * {@code invoiceId}, and returns them summed per month and meter, in the database. A charge
     * another invoice has claimed is skipped: a concurrent claim waits for the row lock and then finds
     * {@code invoice_id} set, so each charge is billed exactly once.
     */
    public List<UsageRow> claim(long invoiceId, String accountId, BillingPeriod period) {
        return jdbc.sql("WITH claimed AS (UPDATE charges SET invoice_id = :invoice "
                        + "WHERE account_id = :account AND invoice_id IS NULL AND " + BILLABLE
                        + " RETURNING meter, quantity, amount, " + MONTH_OF_CHARGE + " AS period_start) "
                        + "SELECT period_start, meter, SUM(quantity) AS quantity, SUM(amount) AS amount FROM claimed "
                        + "GROUP BY period_start, meter")
                .param("invoice", invoiceId)
                .param("account", accountId)
                .param("start", utc(period.start()))
                .param("end", utc(period.end()))
                .query((rs, row) -> new UsageRow(
                        BillingPeriod.containing(rs.getTimestamp("period_start").toInstant()),
                        rs.getString("meter"),
                        rs.getLong("quantity"),
                        new Money(rs.getBigDecimal("amount"))))
                .list();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
