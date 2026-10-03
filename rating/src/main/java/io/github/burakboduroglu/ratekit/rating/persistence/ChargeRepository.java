package io.github.burakboduroglu.ratekit.rating.persistence;

import io.github.burakboduroglu.ratekit.common.UsageEvent;
import io.github.burakboduroglu.ratekit.rating.domain.BillingPeriod;
import io.github.burakboduroglu.ratekit.rating.domain.Charge;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ChargeRepository {

    private final JdbcClient jdbc;

    ChargeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Units of {@code meter} the account was already charged for inside the period. */
    public long unitsUsedInPeriod(String accountId, String meter, BillingPeriod period) {
        return jdbc.sql("SELECT COALESCE(SUM(quantity), 0) FROM charges "
                        + "WHERE account_id = ? AND meter = ? AND occurred_at >= ? AND occurred_at < ?")
                .params(accountId, meter, utc(period.start()), utc(period.end()))
                .query(Long.class)
                .single();
    }

    public void insert(UsageEvent event, Charge charge) {
        jdbc.sql("INSERT INTO charges (account_id, event_id, meter, quantity, amount, tariff_id, occurred_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)")
                .params(event.accountId(), event.eventId(), event.meter(), event.quantity(),
                        charge.amount().amount(), charge.tariffId(), utc(event.occurredAt()))
                .update();
    }

    private static OffsetDateTime utc(java.time.Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
