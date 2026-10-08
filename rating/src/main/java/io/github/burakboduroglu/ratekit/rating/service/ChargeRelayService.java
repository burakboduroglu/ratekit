package io.github.burakboduroglu.ratekit.rating.service;

import io.github.burakboduroglu.ratekit.rating.config.ChargeFeedProperties;
import io.github.burakboduroglu.ratekit.rating.messaging.ChargeFeedPublisher;
import io.github.burakboduroglu.ratekit.rating.repository.ChargeOutboxRepository;
import io.github.burakboduroglu.ratekit.rating.repository.OutboxEntry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Moves charges from the outbox to the {@code charges} topic (ADR 0019).
 *
 * <p>One batch is one database transaction: take the relay lock, read the oldest unsent rows, send
 * them, wait for Kafka's acknowledgements, mark them sent, commit. A failure anywhere rolls the marks
 * back, so the rows are sent again on the next pass: at-least-once, never lost. Only one relay runs at
 * a time across all rating instances (the lock), and it sends in outbox order, which for one account
 * is the order rating charged its events, so each account's charges reach the topic in order.
 *
 * <p>When a pass has emptied the outbox it may write a progress marker: every charge committed before
 * the moment it read the outbox is on the topic now. billing waits for that marker before it
 * invoices a month.
 */
@Service
public class ChargeRelayService {

    private record Batch(boolean locked, int sent, Instant readAt) {
    }

    private final ChargeOutboxRepository outbox;
    private final ChargeFeedPublisher publisher;
    private final ChargeFeedProperties properties;
    private final TransactionTemplate transactions;
    private final Counter sent;
    private Instant lastWatermark;

    public ChargeRelayService(ChargeOutboxRepository outbox, ChargeFeedPublisher publisher, ChargeFeedProperties properties,
                              PlatformTransactionManager transactionManager, MeterRegistry meters) {
        this.outbox = outbox;
        this.publisher = publisher;
        this.properties = properties;
        this.transactions = new TransactionTemplate(transactionManager);
        this.sent = Counter.builder("ratekit.charge_feed.sent")
                .description("Charges relayed from the outbox to the charges topic")
                .register(meters);
    }

    /** @return how many charges this pass sent */
    public synchronized int relayPending() {
        int total = 0;
        while (true) {
            Batch batch = transactions.execute(status -> relayBatch());
            if (!batch.locked()) {
                return total; // another relay is running; it also writes the markers
            }
            total += batch.sent();
            if (batch.sent() < properties.batchSize()) {
                // everything committed before readAt was visible to this batch's read, so it is sent
                markProgress(batch.readAt());
                return total;
            }
        }
    }

    private Batch relayBatch() {
        if (!outbox.tryLockRelay()) {
            return new Batch(false, 0, null);
        }
        Instant readAt = outbox.databaseNow();
        List<OutboxEntry> entries = outbox.unsent(properties.batchSize());
        if (!entries.isEmpty()) {
            publisher.publish(entries.stream().map(OutboxEntry::charge).toList());
            outbox.markSent(entries.stream().map(OutboxEntry::id).toList());
            sent.increment(entries.size());
        }
        return new Batch(true, entries.size(), readAt);
    }

    private void markProgress(Instant publishedThrough) {
        if (lastWatermark != null && publishedThrough.isBefore(lastWatermark.plus(properties.watermarkInterval()))) {
            return;
        }
        publisher.publishWatermark(publishedThrough);
        lastWatermark = publishedThrough;
    }
}
