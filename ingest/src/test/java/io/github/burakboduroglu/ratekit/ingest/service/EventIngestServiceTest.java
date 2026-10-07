package io.github.burakboduroglu.ratekit.ingest.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import io.github.burakboduroglu.ratekit.common.UsageEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.github.burakboduroglu.ratekit.ingest.config.EventTimeProperties;
import io.github.burakboduroglu.ratekit.ingest.exception.EventPublishException;
import io.github.burakboduroglu.ratekit.ingest.exception.EventTimeOutOfRangeException;
import io.github.burakboduroglu.ratekit.ingest.messaging.EventPublisher;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class EventIngestServiceTest {

    private final EventPublisher publisher = mock(EventPublisher.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final EventTimeWindow window = new EventTimeWindow(
            Clock.fixed(Instant.parse("2026-10-03T12:00:00Z"), ZoneOffset.UTC),
            new EventTimeProperties(Duration.ofMinutes(5), Duration.ofHours(1)));
    private final EventIngestService service = new EventIngestService(window, publisher, meters);
    private final UsageEvent event = new UsageEvent("e1", "acc-1", "sms", 1, Instant.parse("2026-10-03T10:00:00Z"));

    @Test
    void handsTheEventToThePublisher() {
        service.accept(event);

        verify(publisher).publish(event);
    }

    @Test
    void countsAcceptedEventsAndOnlyThose() {
        service.accept(event);
        service.accept(event);
        doThrow(new EventPublishException("down", new RuntimeException())).when(publisher).publish(event);
        assertThatThrownBy(() -> service.accept(event)).isInstanceOf(EventPublishException.class);

        org.assertj.core.api.Assertions.assertThat(meters.get("ratekit.events.accepted").counter().count()).isEqualTo(2.0);
    }

    @Test
    void anEventOutsideTheTimeWindowIsNeitherPublishedNorCounted() {
        UsageEvent future = new UsageEvent("e2", "acc-1", "sms", 1, Instant.parse("2026-10-04T00:00:00Z"));

        assertThatThrownBy(() -> service.accept(future)).isInstanceOf(EventTimeOutOfRangeException.class);

        verifyNoInteractions(publisher);
        org.assertj.core.api.Assertions.assertThat(meters.get("ratekit.events.accepted").counter().count()).isZero();
    }

    @Test
    void letsAPublishFailureReachTheCaller() {
        doThrow(new EventPublishException("down", new RuntimeException())).when(publisher).publish(event);

        assertThatThrownBy(() -> service.accept(event)).isInstanceOf(EventPublishException.class);
    }
}
