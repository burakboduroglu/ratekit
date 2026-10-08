package io.github.burakboduroglu.ratekit.billing.repository;

import io.github.burakboduroglu.ratekit.common.ChargeFeedWatermark;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The newest progress marker seen on each partition of the charge feed (ADR 0019). */
@Repository
public class ChargeFeedWatermarkRepository {

    private final JdbcClient jdbc;

    ChargeFeedWatermarkRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Moves the partition's marker forward; a redelivered older marker changes nothing. */
    public void advance(int partition, ChargeFeedWatermark watermark) {
        jdbc.sql("""
                        INSERT INTO charge_feed_watermarks (kafka_partition, partition_count, published_through)
                        VALUES (?, ?, ?)
                        ON CONFLICT (kafka_partition) DO UPDATE SET
                            partition_count = GREATEST(charge_feed_watermarks.partition_count, EXCLUDED.partition_count),
                            published_through = GREATEST(charge_feed_watermarks.published_through, EXCLUDED.published_through),
                            received_at = now()""")
                .params(partition, watermark.partitions(), utc(watermark.publishedThrough()))
                .update();
    }

    /**
     * Whether every partition of the topic, as many as the newest marker counted, has a marker at or
     * after {@code moment}. No marker at all means no: rating has not been heard from.
     */
    public boolean allAtOrAfter(Instant moment) {
        return Boolean.TRUE.equals(jdbc.sql("""
                        WITH expected AS (SELECT coalesce(max(partition_count), 0) AS n FROM charge_feed_watermarks)
                        SELECT e.n > 0 AND e.n = (SELECT count(*) FROM charge_feed_watermarks w
                                                  WHERE w.kafka_partition < e.n AND w.published_through >= ?)
                        FROM expected e""")
                .param(utc(moment))
                .query(Boolean.class)
                .single());
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
