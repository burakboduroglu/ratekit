package io.github.burakboduroglu.ratekit.rating;

import io.github.burakboduroglu.ratekit.common.UsageEvent;
import io.github.burakboduroglu.ratekit.rating.domain.BillingPeriod;
import io.github.burakboduroglu.ratekit.rating.domain.Charge;
import io.github.burakboduroglu.ratekit.rating.domain.Rater;
import io.github.burakboduroglu.ratekit.rating.domain.TariffBook;
import io.github.burakboduroglu.ratekit.rating.persistence.ChargeRepository;
import io.github.burakboduroglu.ratekit.rating.persistence.ProcessedEventRepository;
import io.github.burakboduroglu.ratekit.rating.persistence.TariffRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rates one usage event, at most once.
 *
 * <p>Everything runs in a single transaction: if rating fails (unknown account, no tariff), the
 * "processed" record is rolled back too, so the event is not lost and can be retried.
 */
@Service
public class RatingService {

    private static final Logger log = LoggerFactory.getLogger(RatingService.class);

    private final ProcessedEventRepository processed;
    private final TariffRepository tariffs;
    private final ChargeRepository charges;

    RatingService(ProcessedEventRepository processed, TariffRepository tariffs, ChargeRepository charges) {
        this.processed = processed;
        this.tariffs = tariffs;
        this.charges = charges;
    }

    @Transactional
    public void handle(UsageEvent event) {
        if (!processed.markProcessed(event.accountId(), event.eventId())) {
            log.info("duplicate event ignored: account={} event={}", event.accountId(), event.eventId());
            return;
        }
        TariffBook book = new TariffBook(tariffs.findByMeter(event.meter()));
        long usedBefore = charges.unitsUsedInPeriod(
                event.accountId(), event.meter(), BillingPeriod.containing(event.occurredAt()));
        Charge charge = new Rater(book).rate(event, usedBefore);
        charges.insert(event, charge);
    }
}
