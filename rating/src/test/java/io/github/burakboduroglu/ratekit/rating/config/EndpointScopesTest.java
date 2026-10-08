package io.github.burakboduroglu.ratekit.rating.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.burakboduroglu.ratekit.common.ApiScope;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class EndpointScopesTest {

    @ParameterizedTest
    @CsvSource({
            "GET, /v1/accounts/acc-1, ACCOUNTS_READ",
            "HEAD, /v1/accounts/acc-1, ACCOUNTS_READ",
            "POST, /v1/accounts, ACCOUNTS_WRITE",
            "POST, /v1/accounts/acc-1/top-ups, ACCOUNTS_WRITE",
            "GET, /v1/tariffs, TARIFFS_READ",
            "POST, /v1/tariffs, TARIFFS_WRITE",
            "DELETE, /v1/tariffs, TARIFFS_WRITE",
            "POST, /v1/events, NONE",
            "GET, /v1/invoices/acc-1, NONE",
            "GET, /v1/accountsx, NONE"
    })
    void eachEndpointNeedsItsScope(String method, String path, String scope) {
        assertThat(EndpointScopes.required(method, path))
                .isEqualTo("NONE".equals(scope) ? java.util.Optional.empty() : java.util.Optional.of(ApiScope.valueOf(scope)));
    }
}
