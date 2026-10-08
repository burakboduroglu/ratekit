package io.github.burakboduroglu.ratekit.common;

import java.util.Arrays;

/**
 * What an API key may do (ADR 0017). The same names are configured on every service, so they live
 * here; each service decides which of its endpoints needs which scope.
 */
public enum ApiScope {

    EVENTS_WRITE("events:write"),
    ACCOUNTS_READ("accounts:read"),
    ACCOUNTS_WRITE("accounts:write"),
    TARIFFS_READ("tariffs:read"),
    TARIFFS_WRITE("tariffs:write"),
    BILLING_READ("billing:read"),
    BILLING_WRITE("billing:write");

    private final String value;

    ApiScope(String value) {
        this.value = value;
    }

    /** The name used in configuration and in error messages, such as {@code events:write}. */
    public String value() {
        return value;
    }

    /** @throws IllegalArgumentException if {@code value} is not a known scope name */
    public static ApiScope parse(String value) {
        return Arrays.stream(values())
                .filter(scope -> scope.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown API scope '" + value + "'; known: "
                        + Arrays.stream(values()).map(ApiScope::value).toList()));
    }
}
