package io.github.burakboduroglu.ratekit.rating.domain;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** All known tariff versions; finds the one that applied at a given moment. */
public final class TariffBook {

    private final List<Tariff> tariffs;

    public TariffBook(List<Tariff> tariffs) {
        this.tariffs = List.copyOf(tariffs);
    }

    /** The newest version of {@code meter} whose {@code effectiveFrom} is at or before {@code when}. */
    public Optional<Tariff> at(String meter, Instant when) {
        return tariffs.stream()
                .filter(t -> t.meter().equals(meter))
                .filter(t -> !t.effectiveFrom().isAfter(when))
                .max(Comparator.comparing(Tariff::effectiveFrom));
    }
}
