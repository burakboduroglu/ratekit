package io.github.burakboduroglu.ratekit.billing.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.burakboduroglu.ratekit.billing.config.BillingProperties;
import io.github.burakboduroglu.ratekit.billing.exception.RatingNotCaughtUpException;
import io.github.burakboduroglu.ratekit.billing.repository.ChargeUsageRepository;
import io.github.burakboduroglu.ratekit.common.BillingPeriod;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** The order of the checks before a run: month ended, grace over, rating caught up. */
class InvoiceRunServiceTest {

    private static final BillingPeriod SEPTEMBER = BillingPeriod.of(YearMonth.of(2026, 9));

    private final ChargeUsageRepository usage = mock(ChargeUsageRepository.class);
    private final RatingProgress progress = mock(RatingProgress.class);
    private final InvoiceService invoices = mock(InvoiceService.class);
    private final BillingProperties properties = new BillingProperties(500,
            new BillingProperties.Scheduler(false, "0 0 2-23 1 * *"),
            new BillingProperties.RatingProgress(true, "ratekit-rating", Duration.ofHours(1), Duration.ofSeconds(10)));

    @Test
    void duringTheGraceTheRunIsRefusedWithoutAskingKafka() {
        // 00:30 on 1 October: ingest may still accept September usage until 01:00
        InvoiceRunService service = at("2026-10-01T00:30:00Z");

        assertThatThrownBy(() -> service.run(SEPTEMBER))
                .isInstanceOf(RatingNotCaughtUpException.class)
                .hasMessageContaining("2026-10-01T01:00:00Z");
        verifyNoInteractions(progress, usage);
    }

    @Test
    void afterTheGraceKafkaIsAskedAboutEverythingWrittenBeforeTheCutoff() {
        when(progress.caughtUpTo(any())).thenReturn(false);

        assertThatThrownBy(() -> at("2026-10-01T02:00:00Z").run(SEPTEMBER)).isInstanceOf(RatingNotCaughtUpException.class);
        verify(progress).caughtUpTo(Instant.parse("2026-10-01T01:00:00Z"));
        verifyNoInteractions(usage);
    }

    private InvoiceRunService at(String now) {
        return new InvoiceRunService(usage, progress, invoices, properties,
                Clock.fixed(Instant.parse(now), ZoneOffset.UTC), new SimpleMeterRegistry());
    }
}
