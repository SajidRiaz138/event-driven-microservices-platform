package com.sajidriaz.orderplatform.orderservice.security;

import com.sajidriaz.orderplatform.common.observability.CorrelationContext;
import com.sajidriaz.orderplatform.common.web.ProblemJson;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Renders 401s as RFC 9457 {@code application/problem+json}, matching
 * {@code ProblemDetailExceptionHandler}'s shape.
 *
 * <p>Needed because a missing, malformed, expired or wrongly-audienced token is rejected by
 * the security filter chain, which runs before the {@code DispatcherServlet} and so never
 * reaches {@code @RestControllerAdvice}. Spring Security's default response there is a bare
 * {@code WWW-Authenticate} challenge with an empty body — correct HTTP, but a different error
 * contract from every other status this API returns. Clients should not have to parse one
 * format for 401 and another for 400.
 *
 * <p>The {@code WWW-Authenticate} challenge is preserved: it is what tells a client this is a
 * bearer-token API, and RFC 6750 requires it on a 401.
 */
@Component
public class ProblemDetailAuthenticationEntryPoint implements AuthenticationEntryPoint
{

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authenticationException) throws IOException
    {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"order-platform\"");
        response.setContentType(ProblemJson.CONTENT_TYPE);
        // The reason is deliberately not echoed: "signature mismatch" versus "expired" tells
        // an attacker which half of a forged token to fix. The detail is logged, not returned.
        response.getWriter()
                .write(ProblemJson.render(
                        HttpStatus.UNAUTHORIZED.value(),
                        "unauthenticated",
                        "Unauthorized",
                        "A valid credential is required.",
                        request.getRequestURI(),
                        CorrelationContext.currentCorrelationId()));
    }
}
