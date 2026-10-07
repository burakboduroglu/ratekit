package io.github.burakboduroglu.ratekit.rating.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class RetentionPropertiesTest {

    @Test
    void refusesAnAgeShortEnoughToReRateARetriedEvent() {
        assertThatThrownBy(() -> new RetentionProperties(true, Duration.ofDays(34), 1000, "0 30 3 * * *"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least PT840H");
        assertThatCode(() -> new RetentionProperties(true, Duration.ofDays(35), 1000, "0 30 3 * * *"))
                .doesNotThrowAnyException();
    }

    @Test
    void refusesABatchSizeOfZero() {
        assertThatThrownBy(() -> new RetentionProperties(true, Duration.ofDays(90), 0, "0 30 3 * * *"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
