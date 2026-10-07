package io.github.burakboduroglu.ratekit.rating.repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Deletes records that no longer protect anything (ADR 0014). */
@Repository
public class RetentionRepository {

    private final JdbcClient jdbc;

    RetentionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Deletes up to {@code limit} events rejected before {@code before}: the rejection and its
     * idempotency row, in one statement, so neither is ever left without the other. A row in
     * {@code processed_events} that has a charge is never touched: the charge is the accounting record
     * billing reads, and its foreign key keeps the row.
     *
     * @return how many events were deleted (each one row in each table)
     */
    public int deleteRejectedBatch(Instant before, int limit) {
        // both deletes run in one statement, so the foreign key from rejected_events is checked
        // after both, when neither row exists any more
        return jdbc.sql("""
                        WITH doomed AS (
                            SELECT account_id, event_id FROM rejected_events
                            WHERE rejected_at < ? ORDER BY rejected_at LIMIT ?
                        ), rejected AS (
                            DELETE FROM rejected_events r USING doomed d
                            WHERE r.account_id = d.account_id AND r.event_id = d.event_id
                            RETURNING r.account_id, r.event_id
                        ), processed AS (
                            DELETE FROM processed_events p USING rejected r
                            WHERE p.account_id = r.account_id AND p.event_id = r.event_id
                            RETURNING 1
                        )
                        SELECT count(*) FROM processed""")
                .params(OffsetDateTime.ofInstant(before, ZoneOffset.UTC), limit)
                .query(Integer.class)
                .single();
    }
}
