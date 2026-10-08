package io.github.burakboduroglu.ratekit.billing.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.burakboduroglu.ratekit.billing.config.BillingProperties;
import io.github.burakboduroglu.ratekit.billing.exception.ChargesInFlightException;
import io.github.burakboduroglu.ratekit.billing.exception.RatingNotCaughtUpException;
import io.github.burakboduroglu.ratekit.billing.repository.ChargeUsageRepository;
import io.github.burakboduroglu.ratekit.billing.repository.InvoicedPeriodRepository;
import io.github.burakboduroglu.ratekit.common.BillingPeriod;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** The order of the checks before a run: month ended, grace over, rating caught up, charges delivered. */
class InvoiceRunServiceTest {

    private static final BillingPeriod SEPTEMBER = BillingPeriod.of(YearMonth.of(2026, 9));

    private final ChargeUsageRepository usage = mock(ChargeUsageRepository.class);
    private final RatingProgress progress = mock(RatingProgress.class);
    private final ChargeFeedProgress chargeFeed = mock(ChargeFeedProgress.class);
    private final InvoiceService invoices = mock(InvoiceService.class);
    private final InvoicedPeriodRepository invoicedPeriods = mock(InvoicedPeriodRepository.class);
    private final BillingProperties properties = new BillingProperties(500,
            new BillingProperties.Scheduler(false, "0 0 2-23 1 * *"),
            new BillingProperties.RatingProgress(true, "ratekit-rating", Duration.ofHours(1), Duration.ofSeconds(10),
                    Duration.ofSeconds(1), Duration.ofSeconds(30)));

    @Test
    void duringTheGraceTheRunIsRefusedWithoutAskingKafka() {
        // 00:30 on 1 October: ingest may still accept September usage until 01:00
        InvoiceRunService service = at("2026-10-01T00:30:00Z");

        assertThatThrownBy(() -> service.run(SEPTEMBER))
                .isInstanceOf(RatingNotCaughtUpException.class)
                .hasMessageContaining("2026-10-01T01:00:00Z");
        verifyNoInteractions(progress, chargeFeed, usage);
    }

    @Test
    void afterTheGraceKafkaIsAskedAboutEverythingWrittenBeforeTheCutoff() {
        when(progress.caughtUpTo(any())).thenReturn(false);

        assertThatThrownBy(() -> at("2026-10-01T02:00:00Z").run(SEPTEMBER)).isInstanceOf(RatingNotCaughtUpException.class);
        verify(progress).caughtUpTo(Instant.parse("2026-10-01T01:00:00Z"));
        verifyNoInteractions(chargeFeed, usage, invoicedPeriods);
    }

    @Test
    void onceRatingHasCaughtUpTheChargesRatedUntilNowMustHaveReachedBilling() {
        when(progress.caughtUpTo(any())).thenReturn(true);
        when(chargeFeed.deliveredUpTo(any())).thenReturn(false);

        assertThatThrownBy(() -> at("2026-10-01T02:00:00Z").run(SEPTEMBER))
                .isInstanceOf(ChargesInFlightException.class)
                .hasMessageContaining("2026-10-01T02:00:00Z");
        // not the cutoff: charges for September can be rated, and relayed, long after 01:00
        verify(chargeFeed).deliveredUpTo(Instant.parse("2026-10-01T02:00:00Z"));
        verifyNoInteractions(usage);
    }

    private InvoiceRunService at(String now) {
        return new InvoiceRunService(usage, progress, chargeFeed, invoices, invoicedPeriods, properties,
                Clock.fixed(Instant.parse(now), ZoneOffset.UTC), new SimpleMeterRegistry());
    }
}
