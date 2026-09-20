package com.sajidriaz.orderplatform.orderservice.security;

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
 * Renders insufficient-scope 403s as RFC 9457 {@code application/problem+json}.
 *
 * <p>403 here means the caller is authenticated but the token lacks the scope for this
 * operation — e.g. a read-only token attempting to place an order. It is distinct from the 404
 * returned when a caller asks for an order belonging to somebody else: that one must not
 * reveal that the order exists (S-15), so it is deliberately indistinguishable from a
 * non-existent id.
 */
@Component
public class ProblemDetailAccessDeniedHandler implements AccessDeniedHandler
{

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException
    {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(ProblemJson.CONTENT_TYPE);
        response.getWriter()
                .write(ProblemJson.render(
                        HttpStatus.FORBIDDEN.value(),
                        "insufficient-scope",
                        "Forbidden",
                        "The access token does not grant the scope required for this operation.",
                        request.getRequestURI(),
                        CorrelationContext.currentCorrelationId()));
    }
}
