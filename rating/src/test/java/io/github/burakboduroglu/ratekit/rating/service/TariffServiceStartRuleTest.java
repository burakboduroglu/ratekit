package io.github.burakboduroglu.ratekit.rating.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.burakboduroglu.ratekit.rating.config.TariffCacheProperties;
import io.github.burakboduroglu.ratekit.rating.exception.TariffStartsTooSoonException;
import io.github.burakboduroglu.ratekit.rating.mapper.TariffMapper;
import io.github.burakboduroglu.ratekit.rating.repository.TariffRepository;
import io.github.burakboduroglu.ratekit.rating.repository.TariffRow;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** A new version starts no earlier than now plus the cache TTL (ADR 0012), so every instance's cache has expired. */
class TariffServiceStartRuleTest {

    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");
    private static final Duration TTL = Duration.ofSeconds(30);

    private final TariffRepository repository = mock(TariffRepository.class);
    private final TariffMapper mapper = mock(TariffMapper.class);
    private final TariffBookCache cache = mock(TariffBookCache.class);
    private final TariffService service = new TariffService(repository, mapper, Clock.fixed(NOW, ZoneOffset.UTC), cache,
            new TariffCacheProperties(TTL));

    @BeforeEach
    void inATransaction() {
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void outOfTheTransaction() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    void anOmittedStartIsNowPlusTheTtl() {
        stored(NOW.plus(TTL));

        TariffRow row = service.add("sms", "FLAT", null, "{}");

        assertThat(row.effectiveFrom()).isEqualTo(NOW.plus(TTL));
        verify(repository).insertIfAbsent("sms", "FLAT", NOW.plus(TTL), "{}");
    }

    @Test
    void aStartExactlyOneTtlAheadIsAccepted() {
        stored(NOW.plus(TTL));

        assertThat(service.add("sms", "FLAT", NOW.plus(TTL), "{}").effectiveFrom()).isEqualTo(NOW.plus(TTL));
    }

    @Test
    void aStartLaterThanThatIsAccepted() {
        stored(NOW.plusSeconds(3600));

        assertThat(service.add("sms", "FLAT", NOW.plusSeconds(3600), "{}").effectiveFrom()).isEqualTo(NOW.plusSeconds(3600));
    }

    @Test
    void aStartEvenOneMillisecondBeforeThatIsRefusedAndNothingIsStored() {
        assertThatThrownBy(() -> service.add("sms", "FLAT", NOW.plus(TTL).minusMillis(1), "{}"))
                .isInstanceOf(TariffStartsTooSoonException.class)
                .hasMessageContaining("PT30S");

        verify(repository, never()).insertIfAbsent(anyString(), anyString(), any(), anyString());
    }

    @Test
    void nowAndThePastAreRefusedToo() {
        assertThatThrownBy(() -> service.add("sms", "FLAT", NOW, "{}")).isInstanceOf(TariffStartsTooSoonException.class);
        assertThatThrownBy(() -> service.add("sms", "FLAT", NOW.minusSeconds(1), "{}")).isInstanceOf(TariffStartsTooSoonException.class);
    }

    private void stored(Instant from) {
        when(repository.insertIfAbsent(anyString(), anyString(), any(), anyString())).thenReturn(Optional.of(7L));
        when(repository.rowsByMeter("sms")).thenReturn(List.of(new TariffRow(7L, "sms", "FLAT", from, "{}")));
    }
}
