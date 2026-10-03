package io.github.burakboduroglu.ratekit.rating.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ProcessedEventRepository {

    private final JdbcClient jdbc;

    ProcessedEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records the event as processed.
     *
     * @return true if this is the first time (caller should rate it), false if it was already
     *         recorded (a redelivery to skip)
     */
    public boolean markProcessed(String accountId, String eventId) {
        int inserted = jdbc.sql("INSERT INTO processed_events (account_id, event_id) VALUES (?, ?) "
                        + "ON CONFLICT DO NOTHING")
                .params(accountId, eventId)
                .update();
        return inserted == 1;
    }
}
