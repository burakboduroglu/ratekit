package io.github.burakboduroglu.ratekit.common;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ChargeEventTest {

    private static final Instant T = Instant.parse("2026-10-03T10:00:00Z");

    @Test
    void acceptsARatedAmountAndConvertsItToMoneyExactly() {
        ChargeEvent charge = new ChargeEvent("e1", "acc-1", "sms", 5, new BigDecimal("0.2500"), T, T);

        assertEquals(Money.of("0.25"), charge.money());
        assertDoesNotThrow(() -> new ChargeEvent("e1", "acc-1", "sms", 5, BigDecimal.ZERO, T, T));
    }

    @Test
    void rejectsAnAmountThatWouldNeedRoundingOrIsNegative() {
        assertThrows(IllegalArgumentException.class,
                () -> new ChargeEvent("e1", "acc-1", "sms", 5, new BigDecimal("0.00001"), T, T));
        assertThrows(IllegalArgumentException.class,
                () -> new ChargeEvent("e1", "acc-1", "sms", 5, new BigDecimal("-0.01"), T, T));
    }

    @Test
    void rejectsMissingFields() {
        assertThrows(IllegalArgumentException.class, () -> new ChargeEvent(" ", "acc-1", "sms", 5, BigDecimal.ONE, T, T));
        assertThrows(IllegalArgumentException.class, () -> new ChargeEvent("e1", "acc-1", "sms", 0, BigDecimal.ONE, T, T));
        assertThrows(NullPointerException.class, () -> new ChargeEvent("e1", "acc-1", "sms", 5, BigDecimal.ONE, T, null));
    }

    @Test
    void theTypeMappingNamesTheRealClasses() {
        assertTrue(Topics.CHARGES_TYPE_MAPPING.contains("charge:" + ChargeEvent.class.getName()));
        assertTrue(Topics.CHARGES_TYPE_MAPPING.contains("watermark:" + ChargeFeedWatermark.class.getName()));
    }
}
