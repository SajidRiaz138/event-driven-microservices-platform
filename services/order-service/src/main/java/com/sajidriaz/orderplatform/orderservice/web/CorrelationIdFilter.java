package com.sajidriaz.orderplatform.orderservice.web;

import com.sajidriaz.orderplatform.common.observability.CorrelationContext;
import com.sajidriaz.orderplatform.orderservice.observability.TraceparentContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Adopts an incoming {@code X-Correlation-Id} or generates a fresh one, puts it in the
 * {@link CorrelationContext} MDC for the duration of the request (so every log line
 * carries it), and always sets it on the response — success or error
 * (REST-API-GUIDE §1 "Correlation & tracing", ADR-0013).
 *
 * <p>Ordered ahead of Spring Security's filter chain (which registers at
 * {@code SecurityProperties.DEFAULT_FILTER_ORDER}, -100). Without that, a request rejected by
 * the security chain would return before this filter ran, and the 401/403 would carry neither
 * the {@code X-Correlation-Id} response header nor a {@code correlationId} in its problem body
 * — losing exactly the identifier needed to trace a rejected call. Authentication failures are
 * the responses users most often ask for help with, so they are the last ones that should be
 * untraceable.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String incoming = request.getHeader(CorrelationContext.HEADER_CORRELATION_ID);
        String correlationId = CorrelationContext.adoptOrGenerate(incoming);
        response.setHeader(CorrelationContext.HEADER_CORRELATION_ID, correlationId);

        // Distinct from the business correlation id (ADR-0013): the W3C traceparent is
        // the technical trace context, propagated onto every Envelope produced while
        // handling this request, and its trace id goes to the MDC so logs join up.
        String traceparent = request.getHeader(TraceparentContext.HEADER_TRACEPARENT);
        TraceparentContext.set(traceparent);
        CorrelationContext.setTraceId(TraceparentContext.traceIdOf(traceparent));

        try {
            filterChain.doFilter(request, response);
        } finally {
            TraceparentContext.clear();
            CorrelationContext.clear();
        }
    }
}
