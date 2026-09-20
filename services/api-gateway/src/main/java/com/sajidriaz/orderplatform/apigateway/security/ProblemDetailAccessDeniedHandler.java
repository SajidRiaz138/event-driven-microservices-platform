package com.sajidriaz.orderplatform.apigateway.security;

import com.sajidriaz.orderplatform.common.observability.CorrelationContext;
import com.sajidriaz.orderplatform.common.web.ProblemJson;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Insufficient-scope 403s as RFC 9457 {@code application/problem+json}.
 *
 * <p>403 says the token is valid but does not cover this operation. It never says anything about
 * whether the target resource exists — that is the owning service's call, and order-service
 * deliberately answers 404 for an order belonging to another customer (S-15).
 */
@Component
public class ProblemDetailAccessDeniedHandler implements AccessDeniedHandler {

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                        AccessDeniedException accessDeniedException) throws IOException {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(ProblemJson.CONTENT_TYPE);
        response.getWriter().write(ProblemJson.render(
                HttpStatus.FORBIDDEN.value(),
                "insufficient-scope",
                "Forbidden",
                "The access token does not grant the scope required for this operation.",
                request.getRequestURI(),
                CorrelationContext.currentCorrelationId()));
    }
}
