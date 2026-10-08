package io.github.burakboduroglu.ratekit.ingest.config;

import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The callers allowed to use the {@code /v1} API (ADR 0017), bound from {@code ratekit.security}.
 * No keys and no {@code api-key}: the API is open.
 *
 * @param apiKey deprecated single shared key, treated as one caller with every scope
 * @param keys named keys, each with the scopes it grants
 */
@ConfigurationProperties("ratekit.security")
public record ApiKeyProperties(String apiKey, @DefaultValue List<Key> keys) {

    /** One caller: {@code ratekit.security.keys[0].name}, {@code .key} and {@code .scopes} (comma separated). */
    public record Key(String name, String key, Set<String> scopes) {
    }
}
