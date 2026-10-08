package io.github.burakboduroglu.ratekit.rating.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.burakboduroglu.ratekit.common.ApiScope;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

class ApiKeyConfigTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withBean(ObjectMapper.class)
            .withUserConfiguration(ApiKeyConfig.class);

    @Test
    void withoutAnyKeyNothingIsGuarded() {
        context.run(c -> assertThat(c.getBean(FilterRegistrationBean.class).isEnabled()).isFalse());
    }

    @Test
    void withKeysOnlyTheApiIsGuardedSoActuatorAndSwaggerStayOpen() {
        context.withPropertyValues(
                "ratekit.security.keys[0].name=shop",
                "ratekit.security.keys[0].key=k1",
                "ratekit.security.keys[0].scopes=events:write").run(c -> {
            FilterRegistrationBean<?> registration = c.getBean(FilterRegistrationBean.class);
            assertThat(registration.isEnabled()).isTrue();
            assertThat(registration.getFilter()).isInstanceOf(ApiKeyFilter.class);
            assertThat(registration.getUrlPatterns()).containsExactly("/v1/*");
        });
    }

    @Test
    void keysAreBoundWithTheirNamesAndScopes() {
        context.withPropertyValues(
                "ratekit.security.keys[0].name=shop",
                "ratekit.security.keys[0].key=k1",
                "ratekit.security.keys[0].scopes=events:write",
                "ratekit.security.keys[1].name=operator",
                "ratekit.security.keys[1].key=k2",
                "ratekit.security.keys[1].scopes=accounts:read, tariffs:write").run(c -> {
            List<ApiCaller> callers = ApiKeyConfig.callers(c.getBean(ApiKeyProperties.class));
            assertThat(callers).extracting(ApiCaller::name).containsExactly("shop", "operator");
            assertThat(callers.get(0).scopes()).containsExactly(ApiScope.EVENTS_WRITE);
            assertThat(callers.get(1).scopes()).containsExactlyInAnyOrder(ApiScope.ACCOUNTS_READ, ApiScope.TARIFFS_WRITE);
        });
    }

    @Test
    void theLegacySingleKeyIsOneCallerWithEveryScope() {
        context.withPropertyValues("ratekit.security.api-key=old-key").run(c -> {
            assertThat(c.getBean(FilterRegistrationBean.class).isEnabled()).isTrue();
            List<ApiCaller> callers = ApiKeyConfig.callers(c.getBean(ApiKeyProperties.class));
            assertThat(callers).hasSize(1);
            assertThat(callers.get(0).name()).isEqualTo("legacy");
            assertThat(callers.get(0).scopes()).containsExactlyInAnyOrder(ApiScope.values());
            assertThat(callers.get(0).matches(ApiCaller.hash("old-key"))).isTrue();
        });
    }

    @Test
    void theLegacyKeyAndNamedKeysCanBeUsedTogether() {
        context.withPropertyValues(
                "ratekit.security.api-key=old-key",
                "ratekit.security.keys[0].name=shop",
                "ratekit.security.keys[0].key=k1",
                "ratekit.security.keys[0].scopes=events:write").run(c ->
                assertThat(ApiKeyConfig.callers(c.getBean(ApiKeyProperties.class))).extracting(ApiCaller::name)
                        .containsExactly("shop", "legacy"));
    }

    @Test
    void aBlankLegacyKeyStopsTheServiceInsteadOfOpeningTheDoor() {
        context.withPropertyValues("ratekit.security.api-key= ").run(c -> assertThat(c).hasFailed());
    }

    @Test
    void mistakesInTheKeyListStopTheService() {
        String[][] mistakes = {
                {"keys[0].name=a", "keys[0].key= ", "keys[0].scopes=events:write"},
                {"keys[0].name=a", "keys[0].key=k", "keys[0].scopes="},
                {"keys[0].name=a", "keys[0].key=k", "keys[0].scopes=events:everything"},
                {"keys[0].key=k", "keys[0].scopes=events:write"},
                {"keys[0].name=a", "keys[0].key=k", "keys[0].scopes=events:write",
                        "keys[1].name=a", "keys[1].key=k2", "keys[1].scopes=events:write"},
                {"keys[0].name=a", "keys[0].key=k", "keys[0].scopes=events:write",
                        "keys[1].name=b", "keys[1].key=k", "keys[1].scopes=events:write"},
        };
        for (String[] mistake : mistakes) {
            String[] properties = java.util.Arrays.stream(mistake).map(p -> "ratekit.security." + p).toArray(String[]::new);

            context.withPropertyValues(properties).run(c -> assertThat(c).as(String.join(" ", mistake)).hasFailed());
        }
    }
}
