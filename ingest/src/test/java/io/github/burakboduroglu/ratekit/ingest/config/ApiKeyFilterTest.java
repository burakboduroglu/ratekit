package io.github.burakboduroglu.ratekit.ingest.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ApiKeyFilterTest {

    private final ApiKeyFilter filter = new ApiKeyFilter("s3cret-key", new ObjectMapper());

    @Test
    void theRightKeyIsLetThrough() throws Exception {
        MockFilterChain chain = new MockFilterChain();

        MockHttpServletResponse response = run("s3cret-key", chain);

        assertThat(chain.getRequest()).as("passed on").isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void aMissingKeyIsRefusedWith401AndAProblemDetail() throws Exception {
        MockFilterChain chain = new MockFilterChain();

        MockHttpServletResponse response = run(null, chain);

        assertThat(chain.getRequest()).as("not passed on").isNull();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
        assertThat(response.getHeader("WWW-Authenticate")).contains("X-Api-Key");
        assertThat(response.getContentAsString()).contains("\"status\":401").contains("X-Api-Key");
    }

    @Test
    void aWrongKeyIsRefused() throws Exception {
        for (String wrong : new String[] {"s3cret-kez", "s3cret-key ", "", "S3CRET-KEY"}) {
            MockFilterChain chain = new MockFilterChain();

            assertThat(run(wrong, chain).getStatus()).as("key '%s'", wrong).isEqualTo(401);
            assertThat(chain.getRequest()).isNull();
        }
    }

    @Test
    void aBlankConfiguredKeyIsAMistakeNotAnOpenDoor() {
        assertThatThrownBy(() -> new ApiKeyFilter(" ", new ObjectMapper())).isInstanceOf(IllegalArgumentException.class);
    }

    private MockHttpServletResponse run(String key, MockFilterChain chain) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/anything");
        if (key != null) {
            request.addHeader(ApiKeyFilter.HEADER, key);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }
}
