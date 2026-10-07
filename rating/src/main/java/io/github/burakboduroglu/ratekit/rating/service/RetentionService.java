package io.github.burakboduroglu.ratekit.rating.service;

import io.github.burakboduroglu.ratekit.rating.config.RetentionProperties;
import io.github.burakboduroglu.ratekit.rating.repository.RetentionRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Removes refused events once they can no longer come back (ADR 0014). Rated events are kept: their
 * charges are the accounting record, and their idempotency rows are tied to them.
 *
 * <p>Each batch is one statement and commits on its own, so a long backlog is worked off in short
 * steps instead of one long transaction, and a stop half-way loses nothing.
 */
@Service
public class RetentionService {

    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);

    private final RetentionRepository retention;
    private final RetentionProperties properties;
    private final Clock clock;
    private final Counter rejectedDeleted;
    private final Counter processedDeleted;

    public RetentionService(RetentionRepository retention, RetentionProperties properties, Clock clock, MeterRegistry meters) {
        this.retention = retention;
        this.properties = properties;
        this.clock = clock;
        this.rejectedDeleted = deletedCounter(meters, "rejected_events");
        this.processedDeleted = deletedCounter(meters, "processed_events");
    }

    /** @return how many rejected events were deleted */
    public int pruneRejected() {
        Instant before = clock.instant().minus(properties.rejectedMaxAge());
        int total = 0;
        int deleted;
        do {
            deleted = retention.deleteRejectedBatch(before, properties.batchSize());
            total += deleted;
            rejectedDeleted.increment(deleted);
            processedDeleted.increment(deleted);
        } while (deleted == properties.batchSize());
        log.info("retention: deleted {} rejected events (and their idempotency rows) rejected before {}", total, before);
        return total;
    }

    private static Counter deletedCounter(MeterRegistry meters, String table) {
        return Counter.builder("ratekit.retention.deleted")
                .description("Rows removed by the retention cleanup, by table")
                .tag("table", table)
                .register(meters);
    }
}
