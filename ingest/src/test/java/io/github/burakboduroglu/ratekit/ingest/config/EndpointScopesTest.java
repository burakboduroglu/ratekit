package io.github.burakboduroglu.ratekit.ingest.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.burakboduroglu.ratekit.common.ApiScope;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class EndpointScopesTest {

    @ParameterizedTest
    @CsvSource({
            "POST, /v1/events, EVENTS_WRITE",
            "POST, /v1/events/, EVENTS_WRITE",
            "GET, /v1/events, EVENTS_WRITE",
            "POST, /v1/eventsx, NONE",
            "POST, /v1/accounts, NONE",
            "POST, /v1, NONE",
            "POST, /actuator/health, NONE"
    })
    void eachEndpointNeedsItsScope(String method, String path, String scope) {
        assertThat(EndpointScopes.required(method, path))
                .isEqualTo("NONE".equals(scope) ? java.util.Optional.empty() : java.util.Optional.of(ApiScope.valueOf(scope)));
    }
}
