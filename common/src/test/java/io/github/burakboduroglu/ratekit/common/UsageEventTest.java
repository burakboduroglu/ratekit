package io.github.burakboduroglu.ratekit.common;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class UsageEventTest {

    private static final Instant T = Instant.parse("2026-10-03T10:00:00Z");

    @Test
    void acceptsAValidEvent() {
        assertDoesNotThrow(() -> new UsageEvent("e1", "acc-1", "sms", 1, T));
    }

    @Test
    void rejectsZeroAndNegativeQuantity() {
        assertThrows(IllegalArgumentException.class, () -> new UsageEvent("e1", "acc-1", "sms", 0, T));
        assertThrows(IllegalArgumentException.class, () -> new UsageEvent("e1", "acc-1", "sms", -5, T));
    }

    @Test
    void rejectsBlankOrMissingText() {
        Executable[] bad = {
            () -> new UsageEvent(null, "acc-1", "sms", 1, T),
            () -> new UsageEvent(" ", "acc-1", "sms", 1, T),
            () -> new UsageEvent("e1", "", "sms", 1, T),
            () -> new UsageEvent("e1", "acc-1", "  ", 1, T),
        };
        for (Executable e : bad) {
            assertThrows(IllegalArgumentException.class, e);
        }
    }

    @Test
    void rejectsMissingTimestamp() {
        assertThrows(NullPointerException.class, () -> new UsageEvent("e1", "acc-1", "sms", 1, null));
    }
}
