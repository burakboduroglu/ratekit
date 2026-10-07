package io.github.burakboduroglu.ratekit.rating.service;

import io.github.burakboduroglu.ratekit.rating.exception.InvalidTariffException;
import io.github.burakboduroglu.ratekit.rating.exception.TariffInThePastException;
import io.github.burakboduroglu.ratekit.rating.exception.TariffVersionExistsException;
import io.github.burakboduroglu.ratekit.rating.mapper.TariffMapper;
import io.github.burakboduroglu.ratekit.rating.repository.TariffRepository;
import io.github.burakboduroglu.ratekit.rating.repository.TariffRow;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Adds tariff versions and lists them.
 *
 * <p>A new version is checked by building its price model with the same {@link TariffMapper} that
 * rating uses when it prices an event, so a tariff that rating could not read is refused at the door
 * instead of dead-lettering every event of its meter later. Versions are never edited: a price change
 * is a new version, and it may not start in the past.
 */
@Service
public class TariffService {

    private final TariffRepository tariffs;
    private final TariffMapper mapper;
    private final Clock clock;
    private final TariffBookCache cache;

    public TariffService(TariffRepository tariffs, TariffMapper mapper, Clock clock, TariffBookCache cache) {
        this.tariffs = tariffs;
        this.mapper = mapper;
        this.clock = clock;
        this.cache = cache;
    }

    /** @param effectiveFrom when the version starts; {@code null} means now */
    @Transactional
    public TariffRow add(String meter, String model, Instant effectiveFrom, String paramsJson) {
        try {
            mapper.toModel(model, paramsJson);
        } catch (RuntimeException e) {
            throw new InvalidTariffException(meter, e);
        }
        Instant now = clock.instant();
        Instant from = effectiveFrom == null ? now : effectiveFrom;
        if (from.isBefore(now)) {
            throw new TariffInThePastException(from, now);
        }
        long id = tariffs.insertIfAbsent(meter, model, from, paramsJson)
                .orElseThrow(() -> new TariffVersionExistsException(meter, from));
        // after the commit, not now: evicting earlier would let the listener reload the old versions
        // before the new row is visible, and keep them for a whole TTL
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cache.evict(meter);
            }
        });
        return tariffs.rowsByMeter(meter).stream().filter(t -> t.id() == id).findFirst().orElseThrow();
    }

    @Transactional(readOnly = true)
    public List<TariffRow> versions(String meter) {
        return tariffs.rowsByMeter(meter);
    }
}
