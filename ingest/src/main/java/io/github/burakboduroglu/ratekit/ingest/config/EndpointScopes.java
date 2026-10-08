package io.github.burakboduroglu.ratekit.ingest.config;

import io.github.burakboduroglu.ratekit.common.ApiScope;
import java.util.Optional;

/**
 * Which scope an endpoint of this service needs (ADR 0017). Every method on {@code /v1/events} needs {@code events:write}.
 *
 * <p>Deny by default: a path under {@code /v1} that is not listed needs no scope that exists, so even
 * a key with every scope gets {@code 403}. A new endpoint area must be added here to be reachable when
 * keys are configured.
 */
final class EndpointScopes {

    private EndpointScopes() {
    }

    /** @param path the request path inside the application, such as {@code /v1/accounts/acc-1} */
    static Optional<ApiScope> required(String method, String path) {
        return switch (area(path)) {
            case "events" -> Optional.of(ApiScope.EVENTS_WRITE);
            default -> Optional.empty();
        };
    }

    /** The first segment after {@code /v1}, or an empty string if there is none. */
    private static String area(String path) {
        if (!path.startsWith("/v1/")) {
            return "";
        }
        int end = path.indexOf('/', 4);
        return end < 0 ? path.substring(4) : path.substring(4, end);
    }
}
