package com.sajidriaz.orderplatform.orderservice.web;

import com.sajidriaz.orderplatform.common.observability.CorrelationContext;
import com.sajidriaz.orderplatform.orderservice.observability.TraceparentContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Adopts an incoming {@code X-Correlation-Id} or generates a fresh one, puts it in the
 * {@link CorrelationContext} MDC for the duration of the request (so every log line
 * carries it), and always sets it on the response — success or error
 * (REST-API-GUIDE §1 "Correlation & tracing", ADR-0013).
 */
@Component
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
