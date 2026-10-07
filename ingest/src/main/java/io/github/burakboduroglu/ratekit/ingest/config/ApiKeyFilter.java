package io.github.burakboduroglu.ratekit.ingest.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Lets a request through only if it carries the shared API key in {@value #HEADER} (ADR 0015).
 * Registered for {@code /v1/*} only, so actuator and Swagger stay open.
 *
 * <p>Both keys are hashed before they are compared, and the hashes are compared with
 * {@link MessageDigest#isEqual}, so the time an answer takes says nothing about how much of a guess
 * was right, not even its length.
 */
public class ApiKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Api-Key";

    private final byte[] expected;
    private final ObjectMapper json;

    public ApiKeyFilter(String apiKey, ObjectMapper json) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("ratekit.security.api-key must not be blank when it is set");
        }
        this.expected = sha256(apiKey);
        this.json = json;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String given = request.getHeader(HEADER);
        if (given != null && MessageDigest.isEqual(expected, sha256(given))) {
            chain.doFilter(request, response);
            return;
        }
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "missing or wrong " + HEADER + " header");
        problem.setInstance(URI.create(request.getRequestURI()));
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "ApiKey header=\"" + HEADER + "\"");
        json.writeValue(response.getOutputStream(), problem);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every JVM has SHA-256", e);
        }
    }
}
