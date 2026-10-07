package io.github.burakboduroglu.ratekit.rating.repository;

import io.github.burakboduroglu.ratekit.common.BillingPeriod;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Running total of units per account, meter and billing period; see {@code V4__usage_counters.sql}. */
@Repository
public class UsageCounterRepository {

    private final JdbcClient jdbc;

    UsageCounterRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Units of {@code meter} the account was already charged for inside the period. */
    public long unitsUsed(String accountId, String meter, BillingPeriod period) {
        return jdbc.sql("SELECT units FROM usage_counters WHERE account_id = ? AND meter = ? AND period_start = ?")
                .params(accountId, meter, start(period))
                .query(Long.class)
                .optional()
                .orElse(0L);
    }

    /** Adds {@code units} in one statement: the first charge of a period creates the row, later ones add to it. */
    public void add(String accountId, String meter, BillingPeriod period, long units) {
        jdbc.sql("INSERT INTO usage_counters (account_id, meter, period_start, units) VALUES (?, ?, ?, ?) "
                        + "ON CONFLICT (account_id, meter, period_start) DO UPDATE SET units = usage_counters.units + EXCLUDED.units")
                .params(accountId, meter, start(period), units)
                .update();
    }

    private static OffsetDateTime start(BillingPeriod period) {
        return OffsetDateTime.ofInstant(period.start(), ZoneOffset.UTC);
    }
}
