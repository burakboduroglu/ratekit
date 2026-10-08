package io.github.burakboduroglu.ratekit.rating.repository;

import io.github.burakboduroglu.ratekit.common.ChargeEvent;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The charge outbox (ADR 0019): rows written with each charge, read and marked sent by the relay. */
@Repository
public class ChargeOutboxRepository {

    /** Any fixed number; it names the one lock all relays in all rating instances take turns on. */
    private static final long RELAY_LOCK = 0x5241_5445_4b49_5401L;

    private final JdbcClient jdbc;

    ChargeOutboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Queues a charge for billing. Must run in the transaction that inserted the charge. */
    public void add(long chargeId) {
        jdbc.sql("INSERT INTO charge_outbox (charge_id) VALUES (?)").param(chargeId).update();
    }

    /**
     * Takes the relay lock for the current transaction, if no other relay holds it. It is released at
     * commit or rollback.
     *
     * @return false if another relay (in this or another rating instance) is running
     */
    public boolean tryLockRelay() {
        return Boolean.TRUE.equals(jdbc.sql("SELECT pg_try_advisory_xact_lock(?)").param(RELAY_LOCK)
                .query(Boolean.class).single());
    }

    /**
     * The database's clock right now. Read before {@link #unsent}: in READ COMMITTED every row that
     * committed before this moment is visible to the next statement.
     */
    public Instant databaseNow() {
        return jdbc.sql("SELECT clock_timestamp()").query(Timestamp.class).single().toInstant();
    }

    /** The oldest unsent rows, in the order their charges were written. */
    public List<OutboxEntry> unsent(int limit) {
        return jdbc.sql("SELECT o.id, c.account_id, c.event_id, c.meter, c.quantity, c.amount, c.occurred_at, "
                        + "c.created_at FROM charge_outbox o JOIN charges c ON c.id = o.charge_id "
                        + "WHERE o.sent_at IS NULL ORDER BY o.id LIMIT ?")
                .param(limit)
                .query((rs, row) -> new OutboxEntry(rs.getLong("id"), new ChargeEvent(
                        rs.getString("event_id"),
                        rs.getString("account_id"),
                        rs.getString("meter"),
                        rs.getLong("quantity"),
                        rs.getBigDecimal("amount"),
                        rs.getTimestamp("occurred_at").toInstant(),
                        rs.getTimestamp("created_at").toInstant())))
                .list();
    }

    public void markSent(List<Long> ids) {
        jdbc.sql("UPDATE charge_outbox SET sent_at = now() WHERE id IN (:ids)").param("ids", ids).update();
    }
}
