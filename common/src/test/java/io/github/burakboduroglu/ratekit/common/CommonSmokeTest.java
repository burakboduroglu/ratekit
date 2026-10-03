package io.github.burakboduroglu.ratekit.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CommonSmokeTest {

    @Test
    void runsOnJava21() {
        assertEquals(21, Runtime.version().feature());
    }
}
