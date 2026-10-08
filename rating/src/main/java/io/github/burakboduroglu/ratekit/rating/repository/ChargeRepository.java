package io.github.burakboduroglu.ratekit.rating.repository;

import io.github.burakboduroglu.ratekit.common.UsageEvent;
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

    /** @return the id of the new charge */
    public long insert(UsageEvent event, Charge charge) {
        return jdbc.sql("INSERT INTO charges (account_id, event_id, meter, quantity, amount, tariff_id, occurred_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id")
                .params(event.accountId(), event.eventId(), event.meter(), event.quantity(),
                        charge.amount().amount(), charge.tariffId(), utc(event.occurredAt()))
                .query(Long.class)
                .single();
    }

    private static OffsetDateTime utc(java.time.Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
