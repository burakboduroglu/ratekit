package io.github.burakboduroglu.ratekit.billing.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

class ApiKeyConfigTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withBean(ObjectMapper.class)
            .withUserConfiguration(ApiKeyConfig.class);

    @Test
    void withoutAConfiguredKeyNothingIsGuarded() {
        context.run(c -> assertThat(c).doesNotHaveBean(FilterRegistrationBean.class));
    }

    @Test
    void withAKeyOnlyTheApiIsGuardedSoActuatorAndSwaggerStayOpen() {
        context.withPropertyValues("ratekit.security.api-key=k").run(c -> {
            FilterRegistrationBean<?> registration = c.getBean(FilterRegistrationBean.class);
            assertThat(registration.getFilter()).isInstanceOf(ApiKeyFilter.class);
            assertThat(registration.getUrlPatterns()).containsExactly("/v1/*");
        });
    }
}
