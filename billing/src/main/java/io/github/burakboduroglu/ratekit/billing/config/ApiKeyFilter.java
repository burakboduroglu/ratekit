package io.github.burakboduroglu.ratekit.billing.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.burakboduroglu.ratekit.common.ApiScope;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

/**
 * Lets a request through only if {@value #HEADER} holds a configured caller's key and that caller has
 * the scope the endpoint needs (ADR 0017): {@code 401} for a missing or unknown key, {@code 403} for a
 * known key without the scope. Registered for {@code /v1/*} only, so actuator and Swagger stay open.
 *
 * <p>The given key is hashed and compared with the hash of every configured key, without stopping at
 * the first match, so the time an answer takes says nothing about how much of a guess was right, which
 * caller it matched or the key's length. Keys are never logged; the caller's name is, and it is in the
 * MDC as {@value #MDC_CALLER} while the request runs.
 */
public class ApiKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Api-Key";
    public static final String MDC_CALLER = "caller";

    private static final Logger log = LoggerFactory.getLogger(ApiKeyFilter.class);

    private final List<ApiCaller> callers;
    private final ObjectMapper json;

    public ApiKeyFilter(List<ApiCaller> callers, ObjectMapper json) {
        this.callers = List.copyOf(callers);
        this.json = json;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String given = request.getHeader(HEADER);
        ApiCaller caller = given == null ? null : identify(given);
        if (caller == null) {
            refuse(request, response, HttpStatus.UNAUTHORIZED, "missing or unknown " + HEADER + " header");
            return;
        }
        String path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        Optional<ApiScope> required = EndpointScopes.required(request.getMethod(), path);
        if (required.isEmpty() || !caller.allows(required.get())) {
            log.debug("caller '{}' refused: {}", caller.name(), required.map(s -> "lacks scope " + s.value()).orElse("no scope grants this endpoint"));
            refuse(request, response, HttpStatus.FORBIDDEN, required
                    .map(scope -> "this key lacks the scope " + scope.value())
                    .orElse("no scope grants access to this endpoint"));
            return;
        }
        log.debug("request from caller '{}' (scope {})", caller.name(), required.get().value());
        MDC.put(MDC_CALLER, caller.name());
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_CALLER);
        }
    }

    /** Checks every configured key, so the time taken does not depend on which one (if any) matched. */
    private ApiCaller identify(String given) {
        byte[] givenHash = ApiCaller.hash(given);
        ApiCaller found = null;
        for (ApiCaller candidate : callers) {
            if (candidate.matches(givenHash)) {
                found = candidate;
            }
        }
        return found;
    }

    private void refuse(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String detail)
            throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setInstance(URI.create(request.getRequestURI()));
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        if (status == HttpStatus.UNAUTHORIZED) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "ApiKey header=\"" + HEADER + "\"");
        }
        json.writeValue(response.getOutputStream(), problem);
    }
}
