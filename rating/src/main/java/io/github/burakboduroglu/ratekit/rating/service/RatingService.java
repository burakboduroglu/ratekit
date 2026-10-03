package io.github.burakboduroglu.ratekit.rating.service;

import io.github.burakboduroglu.ratekit.common.UsageEvent;
import io.github.burakboduroglu.ratekit.common.BillingPeriod;
import io.github.burakboduroglu.ratekit.rating.domain.Charge;
import io.github.burakboduroglu.ratekit.rating.domain.Rater;
import io.github.burakboduroglu.ratekit.rating.domain.TariffBook;
import io.github.burakboduroglu.ratekit.rating.exception.UnknownAccountException;
import io.github.burakboduroglu.ratekit.rating.repository.AccountRepository;
import io.github.burakboduroglu.ratekit.rating.repository.ChargeRepository;
import io.github.burakboduroglu.ratekit.rating.repository.ProcessedEventRepository;
import io.github.burakboduroglu.ratekit.rating.repository.RejectedEventRepository;
import io.github.burakboduroglu.ratekit.rating.repository.TariffRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rates one usage event, at most once, and takes the charge from the prepaid balance.
 *
 * <p>Everything runs in a single transaction: if rating fails (unknown account, no tariff), the
 * "processed" record is rolled back too, so the event is not lost; the Kafka error handler then retries
 * transient failures and dead-letters permanent ones (see {@code config.ConsumerErrorHandlingConfig}). An event the
 * account cannot afford is not a failure: it is recorded as rejected and stays processed.
 */
@Service
public class RatingService {

    /** What happened to an event. */
    public enum Outcome { RATED, REJECTED, DUPLICATE }

    private static final Logger log = LoggerFactory.getLogger(RatingService.class);

    private final ProcessedEventRepository processed;
    private final TariffRepository tariffs;
    private final ChargeRepository charges;
    private final AccountRepository accounts;
    private final RejectedEventRepository rejected;

    RatingService(ProcessedEventRepository processed, TariffRepository tariffs, ChargeRepository charges,
                  AccountRepository accounts, RejectedEventRepository rejected) {
        this.processed = processed;
        this.tariffs = tariffs;
        this.charges = charges;
        this.accounts = accounts;
        this.rejected = rejected;
    }

    @Transactional
    public Outcome handle(UsageEvent event) {
        if (!accounts.exists(event.accountId())) {
            throw new UnknownAccountException(event.accountId());
        }
        if (!processed.markProcessed(event.accountId(), event.eventId())) {
            log.info("duplicate event ignored: account={} event={}", event.accountId(), event.eventId());
            return Outcome.DUPLICATE;
        }
        TariffBook book = new TariffBook(tariffs.findByMeter(event.meter()));
        long usedBefore = charges.unitsUsedInPeriod(
                event.accountId(), event.meter(), BillingPeriod.containing(event.occurredAt()));
        Charge charge = new Rater(book).rate(event, usedBefore);

        if (!accounts.tryDeduct(event.accountId(), charge.amount())) {
            rejected.insert(event, charge.amount(), RejectedEventRepository.INSUFFICIENT_BALANCE);
            log.warn("event rejected, insufficient balance: account={} event={} cost={}",
                    event.accountId(), event.eventId(), charge.amount());
            return Outcome.REJECTED;
        }
        charges.insert(event, charge);
        return Outcome.RATED;
    }
}
