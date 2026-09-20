package com.sajidriaz.orderplatform.apigateway.routing;

import com.sajidriaz.orderplatform.common.observability.CorrelationContext;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Forwards a routed request to its downstream service and returns the response unchanged.
 *
 * <p>Handles every method and every path under {@code /api/**} in one place, because a gateway
 * that re-declares each downstream endpoint stops being a gateway and becomes a second copy of
 * every API it fronts — one that goes stale the moment a service adds a field.
 *
 * <p>What is deliberately <b>not</b> passed through:
 * <ul>
 *   <li><b>Hop-by-hop headers</b> ({@code Connection}, {@code Transfer-Encoding},
 *       {@code Content-Length}, {@code Upgrade}, {@code Expect}, …). They describe this
 *       connection, not the message; forwarding {@code Content-Length} from a request whose body
 *       this gateway has already read is how a proxy desynchronises a downstream parser.</li>
 *   <li><b>A caller-supplied {@code X-Correlation-Id}</b> — the value forwarded is the one the
 *       edge adopted or generated, so exactly one id describes the request everywhere.</li>
 * </ul>
 *
 * <p>The {@code Authorization} header <b>is</b> forwarded, unchanged. The gateway validates the
 * token to fail fast, and the downstream service validates it again (ADR-0009); replacing it
 * with a trusted header here would make every service's own validation unenforceable.
 *
 * <p>Bodies are buffered rather than streamed. At this platform's payload sizes (an order with a
 * handful of lines) that is simpler and lets the gateway set an accurate content length;
 * streaming would matter for large uploads, which this API does not have.
 */
@RestController
public class ProxyController {

    private static final Logger log = LoggerFactory.getLogger(ProxyController.class);

    /** Headers that describe a single connection and must not cross a hop. */
    private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "proxy-connection", "te", "trailer", "transfer-encoding", "upgrade",
            "content-length", "host", "expect");

    private final RouteResolver routeResolver;
    private final RestClient restClient;

    public ProxyController(RouteResolver routeResolver, RestClient downstreamRestClient) {
        this.routeResolver = routeResolver;
        this.restClient = downstreamRestClient;
    }

    @RequestMapping("/api/**")
    public ResponseEntity<byte[]> proxy(HttpServletRequest request) throws IOException {
        String path = request.getRequestURI();
        RouteResolver.ResolvedRoute route = routeResolver.resolve(path)
                .orElseThrow(() -> new NoRouteException(path));

        String query = request.getQueryString();
        URI downstreamUri = URI.create(route.downstreamUri() + (query == null ? "" : "?" + query));
        byte[] requestBody = request.getInputStream().readAllBytes();

        log.debug("Routing {} {} to {} via route '{}'",
                request.getMethod(), path, downstreamUri, route.route().id());

        RestClient.RequestBodySpec requestSpec = restClient
                .method(HttpMethod.valueOf(request.getMethod()))
                .uri(downstreamUri)
                .headers(headers -> copyForwardableHeaders(request, headers));
        RestClient.RequestHeadersSpec<?> readySpec = requestBody.length > 0
                ? requestSpec.body(requestBody)
                : requestSpec;

        try {
            return readySpec.exchange((forwarded, response) -> ResponseEntity
                    .status(response.getStatusCode())
                    .headers(responseHeaders -> copyResponseHeaders(response.getHeaders(), responseHeaders))
                    .body(response.getBody().readAllBytes()), false);
        } catch (ResourceAccessException e) {
            throw new DownstreamUnavailableException(route.route().id(), isTimeout(e), e);
        }
    }

    private void copyForwardableHeaders(HttpServletRequest request, HttpHeaders downstreamHeaders) {
        for (String name : Collections.list(request.getHeaderNames())) {
            if (HOP_BY_HOP_HEADERS.contains(name.toLowerCase(Locale.ROOT))
                    || CorrelationContext.HEADER_CORRELATION_ID.equalsIgnoreCase(name)) {
                continue;
            }
            downstreamHeaders.addAll(name, Collections.list(request.getHeaders(name)));
        }
        String correlationId = CorrelationContext.currentCorrelationId();
        if (correlationId != null) {
            downstreamHeaders.set(CorrelationContext.HEADER_CORRELATION_ID, correlationId);
        }
        // Standard proxy provenance: without it the downstream sees only the gateway's address,
        // so its own rate limiting and audit logs would attribute every request to one client.
        downstreamHeaders.add("X-Forwarded-For", request.getRemoteAddr());
        downstreamHeaders.set("X-Forwarded-Proto", request.getScheme());
        downstreamHeaders.set("X-Forwarded-Host", request.getServerName() + ":" + request.getServerPort());
    }

    private void copyResponseHeaders(HttpHeaders downstreamHeaders, HttpHeaders clientHeaders) {
        downstreamHeaders.forEach((name, values) -> {
            if (!HOP_BY_HOP_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                clientHeaders.addAll(name, List.copyOf(values));
            }
        });
    }

    private boolean isTimeout(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpTimeoutException || cause instanceof java.net.SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }
}
