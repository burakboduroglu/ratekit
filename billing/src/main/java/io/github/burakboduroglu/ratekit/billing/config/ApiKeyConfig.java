package io.github.burakboduroglu.ratekit.billing.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Puts {@link ApiKeyFilter} in front of the API when {@code ratekit.security.api-key} is set (ADR 0015). */
@Configuration
public class ApiKeyConfig {

    @Bean
    @ConditionalOnProperty(prefix = "ratekit.security", name = "api-key")
    FilterRegistrationBean<ApiKeyFilter> apiKeyFilter(@Value("${ratekit.security.api-key}") String apiKey, ObjectMapper json) {
        FilterRegistrationBean<ApiKeyFilter> registration = new FilterRegistrationBean<>(new ApiKeyFilter(apiKey, json));
        // the API only; /actuator and the OpenAPI pages stay open for health checks and docs
        registration.addUrlPatterns("/v1/*");
        registration.setName("apiKeyFilter");
        return registration;
    }
}
