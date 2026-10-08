package io.github.burakboduroglu.ratekit.billing.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.burakboduroglu.ratekit.common.ApiScope;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class EndpointScopesTest {

    @ParameterizedTest
    @CsvSource({
            "GET, /v1/invoices/acc-1, BILLING_READ",
            "POST, /v1/invoices, BILLING_WRITE",
            "POST, /v1/invoice-runs, BILLING_WRITE",
            "GET, /v1/invoice-runs, BILLING_READ",
            "POST, /v1/events, NONE",
            "GET, /v1/accounts/acc-1, NONE",
            "GET, /v1/invoicesx, NONE"
    })
    void eachEndpointNeedsItsScope(String method, String path, String scope) {
        assertThat(EndpointScopes.required(method, path))
                .isEqualTo("NONE".equals(scope) ? java.util.Optional.empty() : java.util.Optional.of(ApiScope.valueOf(scope)));
    }
}
