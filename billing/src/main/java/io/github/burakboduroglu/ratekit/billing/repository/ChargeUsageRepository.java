package io.github.burakboduroglu.ratekit.billing.repository;

import io.github.burakboduroglu.ratekit.common.BillingPeriod;
import io.github.burakboduroglu.ratekit.common.Money;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reads billing's own copy of the charges, which the charge feed fills (ADR 0019). */
@Repository
public class ChargeUsageRepository {

    private final JdbcClient jdbc;

    ChargeUsageRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The next batch of accounts that have charges in the period, in account order, starting after
     * {@code afterAccountId} (keyset paging: stable even while the table grows).
     */
    public List<String> accountsWithCharges(BillingPeriod period, String afterAccountId, int limit) {
        return jdbc.sql("SELECT DISTINCT account_id FROM charges "
                        + "WHERE occurred_at >= :start AND occurred_at < :end AND account_id > :after "
                        + "ORDER BY account_id LIMIT :limit")
                .param("start", utc(period.start()))
                .param("end", utc(period.end()))
                .param("after", afterAccountId)
                .param("limit", limit)
                .query(String.class)
                .list();
    }

    /** Per-meter totals for the given accounts, summed in the database. */
    public List<UsageRow> usageFor(BillingPeriod period, List<String> accountIds) {
        return jdbc.sql("SELECT account_id, meter, SUM(quantity) AS quantity, SUM(amount) AS amount FROM charges "
                        + "WHERE occurred_at >= :start AND occurred_at < :end AND account_id IN (:accounts) "
                        + "GROUP BY account_id, meter")
                .param("start", utc(period.start()))
                .param("end", utc(period.end()))
                .param("accounts", accountIds)
                .query((rs, row) -> new UsageRow(
                        rs.getString("account_id"),
                        rs.getString("meter"),
                        rs.getLong("quantity"),
                        new Money(rs.getBigDecimal("amount"))))
                .list();
    }

    private static OffsetDateTime utc(java.time.Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
