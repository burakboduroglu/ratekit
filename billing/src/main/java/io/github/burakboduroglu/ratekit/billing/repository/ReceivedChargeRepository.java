package io.github.burakboduroglu.ratekit.billing.repository;

import io.github.burakboduroglu.ratekit.common.ChargeEvent;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Writes the charges the feed delivers into billing's own table. */
@Repository
public class ReceivedChargeRepository {

    private final JdbcClient jdbc;

    ReceivedChargeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Stores the charge unless the same (account, event) is already there.
     *
     * @return false if it was a second delivery and nothing was written
     */
    public boolean insertIfAbsent(ChargeEvent charge) {
        return jdbc.sql("INSERT INTO charges (account_id, event_id, meter, quantity, amount, occurred_at, rated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (account_id, event_id) DO NOTHING")
                .params(charge.accountId(), charge.eventId(), charge.meter(), charge.quantity(),
                        charge.money().amount(), utc(charge.occurredAt()), utc(charge.ratedAt()))
                .update() == 1;
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
