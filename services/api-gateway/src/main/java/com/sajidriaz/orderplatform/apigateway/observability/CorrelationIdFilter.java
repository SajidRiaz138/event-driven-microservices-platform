package com.sajidriaz.orderplatform.apigateway.observability;

import com.sajidriaz.orderplatform.common.observability.CorrelationContext;
import com.sajidriaz.orderplatform.common.observability.TraceparentContext;
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
 * Adopts the incoming {@code X-Correlation-Id} or generates one at the edge, and always returns
 * it (REST-API-GUIDE §1, ADR-0013). The gateway is where generation belongs: every request enters
 * here, so an id minted here covers the whole path through the platform, and
 * {@link com.sajidriaz.orderplatform.apigateway.routing.ProxyController} forwards it downstream.
 *
 * <p>Ordered ahead of Spring Security (which registers at -100) so that requests the security
 * chain rejects still carry a correlation id in the response header and in the problem body. A
 * 401 or 429 nobody can trace is the one a support request will be about.
 *
 * <p>The {@code traceparent} is propagated but never invented: minting a trace id with no tracer
 * behind it would advertise spans no backend ever recorded (ADR-0013).
 */
@Component
@Order (Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter
{

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException
    {
        String correlationId = CorrelationContext.adoptOrGenerate(
                request.getHeader(CorrelationContext.HEADER_CORRELATION_ID));
        response.setHeader(CorrelationContext.HEADER_CORRELATION_ID, correlationId);

        String traceparent = request.getHeader(TraceparentContext.HEADER_TRACEPARENT);
        TraceparentContext.set(traceparent);
        CorrelationContext.setTraceId(TraceparentContext.traceIdOf(traceparent));

        try
        {
            filterChain.doFilter(request, response);
        }
        finally
        {
            TraceparentContext.clear();
            CorrelationContext.clear();
        }
    }
}
