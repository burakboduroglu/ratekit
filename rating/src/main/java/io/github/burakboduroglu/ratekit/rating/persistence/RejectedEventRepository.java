package io.github.burakboduroglu.ratekit.rating.persistence;

import io.github.burakboduroglu.ratekit.common.Money;
import io.github.burakboduroglu.ratekit.common.UsageEvent;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RejectedEventRepository {

    public static final String INSUFFICIENT_BALANCE = "INSUFFICIENT_BALANCE";

    private final JdbcClient jdbc;

    RejectedEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UsageEvent event, Money wouldHaveCost, String reason) {
        jdbc.sql("INSERT INTO rejected_events (account_id, event_id, meter, quantity, amount, reason, occurred_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)")
                .params(event.accountId(), event.eventId(), event.meter(), event.quantity(),
                        wouldHaveCost.amount(), reason,
                        OffsetDateTime.ofInstant(event.occurredAt(), ZoneOffset.UTC))
                .update();
    }
}
