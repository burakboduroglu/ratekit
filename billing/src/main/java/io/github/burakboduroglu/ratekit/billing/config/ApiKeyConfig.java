package io.github.burakboduroglu.ratekit.billing.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.burakboduroglu.ratekit.common.ApiScope;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Puts {@link ApiKeyFilter} in front of the API when callers are configured (ADR 0017): keys under
 * {@code ratekit.security.keys}, or the deprecated single {@code ratekit.security.api-key}. With none
 * of them the filter is not registered and the API is open.
 */
@Configuration
@EnableConfigurationProperties(ApiKeyProperties.class)
public class ApiKeyConfig {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyConfig.class);

    @Bean
    FilterRegistrationBean<ApiKeyFilter> apiKeyFilter(ApiKeyProperties properties, ObjectMapper json) {
        List<ApiCaller> callers = callers(properties);
        FilterRegistrationBean<ApiKeyFilter> registration = new FilterRegistrationBean<>(new ApiKeyFilter(callers, json));
        // the API only; /actuator and the OpenAPI pages stay open for health checks and docs
        registration.addUrlPatterns("/v1/*");
        registration.setName("apiKeyFilter");
        registration.setEnabled(!callers.isEmpty());
        return registration;
    }

    /** @throws IllegalArgumentException for a key that is blank, a repeated name or key, or an unknown scope */
    static List<ApiCaller> callers(ApiKeyProperties properties) {
        List<ApiCaller> callers = new ArrayList<>();
        for (ApiKeyProperties.Key key : properties.keys()) {
            callers.add(ApiCaller.of(key.name(), key.key(), key.scopes()));
        }
        if (properties.apiKey() != null) {
            log.warn("ratekit.security.api-key is deprecated and will be removed in the next release: it is "
                    + "treated as one caller named 'legacy' with every scope. Use ratekit.security.keys[n].name, "
                    + ".key and .scopes instead (ADR 0017)");
            callers.add(ApiCaller.of("legacy", properties.apiKey(), Arrays.stream(ApiScope.values()).map(ApiScope::value).toList()));
        }
        Set<String> names = new HashSet<>();
        Set<String> hashes = new HashSet<>();
        for (ApiCaller caller : callers) {
            if (!names.add(caller.name())) {
                throw new IllegalArgumentException("two API keys are named '" + caller.name() + "'");
            }
            if (!hashes.add(HexFormat.of().formatHex(caller.keyHash()))) {
                throw new IllegalArgumentException("caller '" + caller.name() + "' has the same key as another caller");
            }
        }
        return callers;
    }
}
