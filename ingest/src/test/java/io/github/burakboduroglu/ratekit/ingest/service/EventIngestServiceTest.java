package io.github.burakboduroglu.ratekit.ingest.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.github.burakboduroglu.ratekit.common.UsageEvent;
import io.github.burakboduroglu.ratekit.ingest.exception.EventPublishException;
import io.github.burakboduroglu.ratekit.ingest.messaging.EventPublisher;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class EventIngestServiceTest {

    private final EventPublisher publisher = mock(EventPublisher.class);
    private final EventIngestService service = new EventIngestService(publisher);
    private final UsageEvent event = new UsageEvent("e1", "acc-1", "sms", 1, Instant.parse("2026-10-03T10:00:00Z"));

    @Test
    void handsTheEventToThePublisher() {
        service.accept(event);

        verify(publisher).publish(event);
    }

    @Test
    void letsAPublishFailureReachTheCaller() {
        doThrow(new EventPublishException("down", new RuntimeException())).when(publisher).publish(event);

        assertThatThrownBy(() -> service.accept(event)).isInstanceOf(EventPublishException.class);
    }
}
