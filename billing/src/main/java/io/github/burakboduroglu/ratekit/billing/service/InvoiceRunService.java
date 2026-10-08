package io.github.burakboduroglu.ratekit.billing.service;

import io.github.burakboduroglu.ratekit.common.BillingPeriod;
import io.github.burakboduroglu.ratekit.billing.config.BillingProperties;
import io.github.burakboduroglu.ratekit.billing.exception.ChargesInFlightException;
import io.github.burakboduroglu.ratekit.billing.exception.PeriodNotClosedException;
import io.github.burakboduroglu.ratekit.billing.exception.RatingNotCaughtUpException;
import io.github.burakboduroglu.ratekit.billing.repository.ChargeUsageRepository;
import io.github.burakboduroglu.ratekit.billing.repository.InvoicedPeriodRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Invoices every account that used something in a finished period, or has late charges of an
 * earlier invoiced month to bill (ADR 0020).
 *
 * <p>A period is invoiced only after it has ended, after ingest has stopped accepting usage for it
 * (the late-arrival grace), after rating has rated everything accepted until then (ADR 0010) and
 * after the resulting charges have reached billing's own table (ADR 0019). Otherwise the run is
 * refused and changes nothing; the scheduler simply tries again an hour later.
 *
 * <p>Safe to run again for the same period: the unique (account, period) key makes a second
 * attempt skip accounts that already have an invoice, and each invoice is its own transaction, so a
 * failure part-way leaves earlier invoices intact and a rerun finishes the rest. Accounts are
 * processed in batches by account id, so memory use does not grow with the number of accounts.
 * Once every account is done the month is recorded as invoiced: from then on, a charge of it that is
 * still unbilled is late and goes on the account's next invoice as an adjustment.
 */
@Service
public class InvoiceRunService {

    /** Outcome of a run. */
    public record RunSummary(BillingPeriod period, int created, int alreadyInvoiced) {
    }

    private static final Logger log = LoggerFactory.getLogger(InvoiceRunService.class);

    private final ChargeUsageRepository usage;
    private final RatingProgress ratingProgress;
    private final ChargeFeedProgress chargeFeed;
    private final InvoiceService invoices;
    private final InvoicedPeriodRepository invoicedPeriods;
    private final BillingProperties properties;
    private final Clock clock;
    private final Counter created;
    private final Counter skipped;

    public InvoiceRunService(ChargeUsageRepository usage, RatingProgress ratingProgress, ChargeFeedProgress chargeFeed,
                             InvoiceService invoices, InvoicedPeriodRepository invoicedPeriods, BillingProperties properties,
                             Clock clock, MeterRegistry meters) {
        this.usage = usage;
        this.ratingProgress = ratingProgress;
        this.chargeFeed = chargeFeed;
        this.invoices = invoices;
        this.invoicedPeriods = invoicedPeriods;
        this.properties = properties;
        this.clock = clock;
        this.created = Counter.builder("ratekit.invoices").description("Invoice run results").tag("result", "created").register(meters);
        this.skipped = Counter.builder("ratekit.invoices").description("Invoice run results").tag("result", "already_invoiced").register(meters);
    }

    public RunSummary run(BillingPeriod period) {
        if (period.end().isAfter(clock.instant())) {
            throw new PeriodNotClosedException(period);
        }
        Instant cutoff = period.end().plus(properties.ratingProgress().lateArrivalGrace());
        if (cutoff.isAfter(clock.instant()) || !ratingProgress.caughtUpTo(cutoff)) {
            throw new RatingNotCaughtUpException(period, cutoff);
        }
        // every usage event accepted for the period is now a charge, a rejection or a dead letter in
        // rating, committed before this moment; its charges must also have reached billing's table
        Instant ratedBy = clock.instant();
        if (!chargeFeed.deliveredUpTo(ratedBy)) {
            throw new ChargesInFlightException(period, ratedBy);
        }
        int createdNow = 0;
        int alreadyInvoiced = 0;
        String after = "";
        while (true) {
            List<String> accounts = usage.accountsToInvoice(period, after, properties.batchSize());
            if (accounts.isEmpty()) {
                break;
            }
            for (String account : accounts) {
                switch (invoices.create(account, period)) {
                    case CREATED -> createdNow++;
                    case ALREADY_INVOICED -> alreadyInvoiced++;
                    case NOTHING_TO_BILL -> { }
                }
            }
            after = accounts.get(accounts.size() - 1);
        }
        invoicedPeriods.markCompleted(period);
        created.increment(createdNow);
        skipped.increment(alreadyInvoiced);
        log.info("invoice run for {}: {} created, {} already invoiced", period.month(), createdNow, alreadyInvoiced);
        return new RunSummary(period, createdNow, alreadyInvoiced);
    }
}
