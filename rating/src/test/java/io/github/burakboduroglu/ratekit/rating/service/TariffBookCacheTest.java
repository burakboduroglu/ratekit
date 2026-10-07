package io.github.burakboduroglu.ratekit.rating.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.burakboduroglu.ratekit.rating.config.TariffCacheProperties;
import io.github.burakboduroglu.ratekit.rating.domain.FlatPrice;
import io.github.burakboduroglu.ratekit.rating.domain.Tariff;
import io.github.burakboduroglu.ratekit.rating.repository.TariffRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class TariffBookCacheTest {

    private static final Instant JAN = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant EVENT = Instant.parse("2026-10-07T10:00:00Z");
    private static final Tariff SMS = new Tariff(1, "sms", JAN, new FlatPrice(new BigDecimal("0.05")));

    private final TariffRepository repository = mock(TariffRepository.class);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-07T12:00:00Z"));

    @Test
    void readsTheDatabaseOnceWithinTheTtl() {
        when(repository.findByMeter("sms")).thenReturn(List.of(SMS));
        TariffBookCache cache = cache(Duration.ofSeconds(30));

        cache.bookFor("sms", EVENT);
        clock.advance(Duration.ofSeconds(29));
        cache.bookFor("sms", EVENT);

        verify(repository, times(1)).findByMeter("sms");
    }

    @Test
    void readsAgainOnceTheTtlHasPassed() {
        when(repository.findByMeter("sms")).thenReturn(List.of(SMS));
        TariffBookCache cache = cache(Duration.ofSeconds(30));

        cache.bookFor("sms", EVENT);
        clock.advance(Duration.ofSeconds(30));
        cache.bookFor("sms", EVENT);

        verify(repository, times(2)).findByMeter("sms");
    }

    @Test
    void anEvictedMeterIsReadAgainAtOnce() {
        when(repository.findByMeter("sms")).thenReturn(List.of(SMS));
        TariffBookCache cache = cache(Duration.ofSeconds(30));

        cache.bookFor("sms", EVENT);
        cache.evict("sms");
        cache.bookFor("sms", EVENT);

        verify(repository, times(2)).findByMeter("sms");
    }

    @Test
    void cachedVersionsWithoutOneForTheEventTimeAreReadAgainBeforeGivingUp() {
        // the cache was filled before the meter had any tariff; one was added since, elsewhere
        when(repository.findByMeter("sms")).thenReturn(List.of()).thenReturn(List.of(SMS));
        TariffBookCache cache = cache(Duration.ofSeconds(30));

        assertThat(cache.bookFor("sms", EVENT).at("sms", EVENT)).isEmpty();
        assertThat(cache.bookFor("sms", EVENT).at("sms", EVENT)).contains(SMS);
    }

    @Test
    void aZeroTtlTurnsTheCacheOff() {
        when(repository.findByMeter("sms")).thenReturn(List.of(SMS));
        TariffBookCache cache = cache(Duration.ZERO);

        cache.bookFor("sms", EVENT);
        cache.bookFor("sms", EVENT);

        verify(repository, times(2)).findByMeter("sms");
    }

    private TariffBookCache cache(Duration ttl) {
        return new TariffBookCache(repository, clock, new TariffCacheProperties(ttl));
    }

    /** A clock the test moves by hand. */
    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
