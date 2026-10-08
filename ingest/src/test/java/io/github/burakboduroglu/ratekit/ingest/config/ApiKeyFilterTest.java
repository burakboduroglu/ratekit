package io.github.burakboduroglu.ratekit.ingest.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.burakboduroglu.ratekit.common.ApiScope;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ApiKeyFilterTest {

    private static final List<ApiCaller> CALLERS = List.of(
            ApiCaller.of("full", "full-key", Arrays.stream(ApiScope.values()).map(ApiScope::value).toList()),
            ApiCaller.of("limited", "limited-key", List.of(ApiScope.EVENTS_WRITE.value())),
            ApiCaller.of("wrong-scope", "wrong-scope-key", List.of(ApiScope.ACCOUNTS_READ.value())));

    private final ApiKeyFilter filter = new ApiKeyFilter(CALLERS, new ObjectMapper());

    @Test
    void aKnownKeyWithTheScopeIsLetThrough() throws Exception {
        MockFilterChain chain = new MockFilterChain();

        assertThat(run("limited-key", "POST", "/v1/events", chain).getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).as("passed on").isNotNull();
    }

    @Test
    void everyCallerIsFoundNotJustTheFirst() throws Exception {
        for (String key : new String[] {"full-key", "limited-key"}) {
            MockFilterChain chain = new MockFilterChain();

            run(key, "POST", "/v1/events", chain);

            assertThat(chain.getRequest()).as(key).isNotNull();
        }
    }

    @Test
    void aMissingKeyIsRefusedWith401AndAProblemDetail() throws Exception {
        MockFilterChain chain = new MockFilterChain();

        MockHttpServletResponse response = run(null, "POST", "/v1/events", chain);

        assertThat(chain.getRequest()).as("not passed on").isNull();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
        assertThat(response.getHeader("WWW-Authenticate")).contains("X-Api-Key");
        assertThat(response.getContentAsString()).contains("\"status\":401").contains("X-Api-Key");
    }

    @Test
    void anUnknownKeyIsRefusedWith401() throws Exception {
        for (String wrong : new String[] {"full-kez", "full-key ", "", "FULL-KEY", "limited"}) {
            MockFilterChain chain = new MockFilterChain();

            assertThat(run(wrong, "POST", "/v1/events", chain).getStatus()).as("key '%s'", wrong).isEqualTo(401);
            assertThat(chain.getRequest()).isNull();
        }
    }

    @Test
    void aKnownKeyWithoutTheScopeIsRefusedWith403AndNothingElse() throws Exception {
        MockFilterChain chain = new MockFilterChain();

        MockHttpServletResponse response = run("wrong-scope-key", "POST", "/v1/events", chain);

        assertThat(chain.getRequest()).as("not passed on").isNull();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
        assertThat(response.getHeader("WWW-Authenticate")).isNull();
        assertThat(response.getContentAsString()).contains("\"status\":403").contains("events:write").doesNotContain("wrong-scope-key");
    }

    @Test
    void aPathNoScopeCoversIsRefusedEvenForAKeyWithEveryScope() throws Exception {
        MockFilterChain chain = new MockFilterChain();

        assertThat(run("full-key", "GET", "/v1/nothing-here", chain).getStatus()).isEqualTo(403);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void theCallerNameIsInTheMdcWhileTheRequestRunsAndGoneAfter() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        MockHttpServletRequest request = request("limited-key", "POST", "/v1/events");

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> seen.set(MDC.get("caller")));

        assertThat(seen.get()).isEqualTo("limited");
        assertThat(MDC.get("caller")).isNull();
    }

    private MockHttpServletResponse run(String key, String method, String path, MockFilterChain chain) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request(key, method, path), response, chain);
        return response;
    }

    private static MockHttpServletRequest request(String key, String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        if (key != null) {
            request.addHeader(ApiKeyFilter.HEADER, key);
        }
        return request;
    }
}
