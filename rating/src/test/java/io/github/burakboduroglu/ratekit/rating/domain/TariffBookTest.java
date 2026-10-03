package io.github.burakboduroglu.ratekit.rating.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class TariffBookTest {

    private static final Instant JAN = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant JUL = Instant.parse("2026-07-01T00:00:00Z");

    private final Tariff smsOld = new Tariff(1, "sms", JAN, new FlatPrice(new BigDecimal("0.05")));
    private final Tariff smsNew = new Tariff(2, "sms", JUL, new FlatPrice(new BigDecimal("0.08")));
    private final Tariff data = new Tariff(3, "data-mb", JAN, new FlatPrice(new BigDecimal("0.01")));
    private final TariffBook book = new TariffBook(List.of(smsNew, data, smsOld)); // order must not matter

    @Test
    void picksTheVersionThatWasInForceAtThatMoment() {
        assertThat(book.at("sms", Instant.parse("2026-03-15T12:00:00Z"))).contains(smsOld);
        assertThat(book.at("sms", Instant.parse("2026-09-01T00:00:00Z"))).contains(smsNew);
    }

    @Test
    void theNewVersionStartsExactlyAtItsEffectiveFrom() {
        assertThat(book.at("sms", JUL.minusNanos(1))).contains(smsOld);
        assertThat(book.at("sms", JUL)).contains(smsNew);
    }

    @Test
    void nothingAppliesBeforeTheFirstVersion() {
        assertThat(book.at("sms", JAN.minusNanos(1))).isEmpty();
        assertThat(book.at("sms", JAN)).contains(smsOld);
    }

    @Test
    void metersAreIndependent() {
        assertThat(book.at("data-mb", Instant.parse("2026-09-01T00:00:00Z"))).contains(data);
        assertThat(book.at("voice-min", JUL)).isEmpty();
    }
}
