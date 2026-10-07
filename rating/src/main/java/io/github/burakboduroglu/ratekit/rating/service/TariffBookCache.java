package io.github.burakboduroglu.ratekit.rating.service;

import io.github.burakboduroglu.ratekit.rating.config.TariffCacheProperties;
import io.github.burakboduroglu.ratekit.rating.domain.TariffBook;
import io.github.burakboduroglu.ratekit.rating.repository.TariffRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

/**
 * Keeps each meter's tariff versions in memory for a short time, so rating does not read the
 * {@code tariffs} table for every event (ADR 0012).
 *
 * <ul>
 *   <li>An entry lives for {@code ratekit.rating.tariff-cache.ttl} (default 30 s); {@code PT0S} turns
 *       the cache off.
 *   <li>A version added through this instance's API evicts its meter once the insert has committed
 *       ({@link TariffService}).
 *   <li>If the cached versions have none that applies at the event's time, the meter is read again
 *       before giving up, so a first tariff added elsewhere never dead-letters events for a whole TTL.
 * </ul>
 */
@Component
public class TariffBookCache {

    private record Entry(TariffBook book, Instant loadedAt) {
    }

    private final TariffRepository tariffs;
    private final Clock clock;
    private final Duration ttl;
    private final ConcurrentMap<String, Entry> byMeter = new ConcurrentHashMap<>();

    public TariffBookCache(TariffRepository tariffs, Clock clock, TariffCacheProperties properties) {
        this.tariffs = tariffs;
        this.clock = clock;
        this.ttl = properties.ttl();
    }

    /** The meter's versions, fresh enough to contain one that applies at {@code when} if the database has one. */
    public TariffBook bookFor(String meter, Instant when) {
        Instant now = clock.instant();
        Entry cached = byMeter.get(meter);
        if (cached != null && now.isBefore(cached.loadedAt().plus(ttl)) && cached.book().at(meter, when).isPresent()) {
            return cached.book();
        }
        TariffBook book = new TariffBook(tariffs.findByMeter(meter));
        if (ttl.isPositive()) {
            byMeter.put(meter, new Entry(book, now));
        }
        return book;
    }

    public void evict(String meter) {
        byMeter.remove(meter);
    }
}
