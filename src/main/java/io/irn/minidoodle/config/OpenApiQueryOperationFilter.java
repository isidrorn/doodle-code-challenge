package io.irn.minidoodle.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Promotes {@link OpenApiQuerySupportConfig}'s {@code x-query} extension to a genuine, top-level
 * {@code query} field in the served {@code /api-docs} JSON — the one thing an {@code
 * OpenApiCustomizer} alone cannot do, because {@code io.swagger.v3.oas.models.PathItem} (from this
 * project's pinned swagger-core, 2.2.47) has no Java field to represent it. Extensions are
 * serialized generically and swagger-core enforces the OpenAPI spec's own rule that extension keys
 * start with {@code x-} — confirmed directly (see design-decisions-v2.md): a bare {@code "query"}
 * extension key is silently dropped during serialization, only an {@code x-}-prefixed one survives.
 *
 * <p>This filter runs <em>after</em> springdoc has already produced its normal document, rewrites
 * the JSON tree directly (not the typed {@code OpenAPI} object — nothing upstream of this needs to
 * change), and:
 * <ol>
 *   <li>Renames each {@code x-query} extension found under {@code paths.*} to a real {@code query}
 *       sibling of {@code get}/{@code post}/etc.</li>
 *   <li>Bumps the document's declared {@code openapi} version to {@code 3.2.0}.</li>
 * </ol>
 *
 * <p>Both steps are required together, not just the first: Swagger UI 5.32.2 (confirmed to be what
 * this project's {@code springdoc-openapi-starter-webmvc-ui} dependency actually resolves to, via
 * {@code mvn dependency:tree} — not assumed) ships its own OpenAPI 3.2 support, including "query" in
 * its internal list of recognized operation methods (found directly in swagger-ui-bundle.js:
 * {@code ["get","put","post","delete","options","head","patch","trace","query"]}) — but that
 * recognition is gated behind an {@code isOAS32()} check, and that check is a strict regex against
 * the document's own {@code openapi} field ({@code /^3\.2\.(?:[1-9]\d*|0)$/}, found in the same
 * bundle), not a capability probe. A real {@code query} key on a document still claiming
 * {@code "openapi":"3.1.0"} would not activate that rendering path.
 *
 * <p>Not yet confirmed with an actual browser screenshot (browser automation was unavailable in the
 * environment this was built in) — confirmed instead by inspecting the exact JS this project's
 * pinned Swagger UI version ships, which is what actually runs in the browser regardless of what
 * springdoc's Java-side OpenAPI version claims. See design-decisions-v2.md for the full trail.
 */
@Configuration
@Slf4j
public class OpenApiQueryOperationFilter {

    private static final String API_DOCS_PATH = "/api-docs";
    private static final String QUERY_EXTENSION_KEY = "x-query";
    private static final String QUERY_METHOD_KEY = "query";
    private static final String OAS_32_VERSION = "3.2.0";

    // Deliberately NOT injecting Spring's autoconfigured ObjectMapper bean: Spring Boot 4.1 defaults
    // that bean to Jackson 3 (com.fasterxml -> tools.jackson's new base package), but springdoc-openapi
    // still serializes /api-docs with classic Jackson 2 (com.fasterxml.jackson.databind, pulled in
    // transitively) — no Spring bean of that type exists in this app, confirmed by the
    // NoSuchBeanDefinitionException hit while wiring this up the first way. A plain local instance is
    // both correct (this filter only does generic JSON tree edits, no custom modules needed) and
    // avoids depending on which Jackson major version happens to be wired as a bean.
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Bean
    public FilterRegistrationBean<OncePerRequestFilter> queryOperationExposureFilter() {
        FilterRegistrationBean<OncePerRequestFilter> registration =
                new FilterRegistrationBean<>(new Filter(objectMapper));
        registration.addUrlPatterns(API_DOCS_PATH);
        return registration;
    }

    @RequiredArgsConstructor
    private static final class Filter extends OncePerRequestFilter {

        private final ObjectMapper objectMapper;

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                         FilterChain filterChain) throws ServletException, IOException {
            ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
            filterChain.doFilter(request, wrapper);

            byte[] body = wrapper.getContentAsByteArray();
            if (body.length == 0 || wrapper.getStatus() != HttpServletResponse.SC_OK) {
                wrapper.copyBodyToResponse();
                return;
            }

            byte[] rewritten = tryPromoteQueryOperations(body);
            if (rewritten == null) {
                wrapper.copyBodyToResponse();
                return;
            }

            response.setContentLength(rewritten.length);
            response.getOutputStream().write(rewritten);
        }

        /** Returns the rewritten body, or {@code null} if nothing needed rewriting (or parsing failed). */
        private byte[] tryPromoteQueryOperations(byte[] body) {
            try {
                JsonNode root = objectMapper.readTree(body);
                if (!(root instanceof ObjectNode rootObject)) {
                    return null;
                }
                JsonNode pathsNode = rootObject.get("paths");
                if (pathsNode == null) {
                    return null;
                }

                boolean promotedAny = false;
                Iterator<Map.Entry<String, JsonNode>> pathEntries = pathsNode.fields();
                while (pathEntries.hasNext()) {
                    Map.Entry<String, JsonNode> entry = pathEntries.next();
                    if (entry.getValue() instanceof ObjectNode pathItem && pathItem.has(QUERY_EXTENSION_KEY)) {
                        pathItem.set(QUERY_METHOD_KEY, pathItem.remove(QUERY_EXTENSION_KEY));
                        promotedAny = true;
                    }
                }

                if (!promotedAny) {
                    return null;
                }

                rootObject.put("openapi", OAS_32_VERSION);
                return objectMapper.writeValueAsBytes(rootObject);
            } catch (IOException | RuntimeException ex) {
                log.warn("Failed to promote x-query extensions to real 'query' operations in /api-docs; "
                        + "serving the unmodified document instead: {}", ex.toString());
                return null;
            }
        }
    }
}
