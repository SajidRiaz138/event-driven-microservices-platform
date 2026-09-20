package com.sajidriaz.orderplatform.apigateway.security;

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
 * 401s as RFC 9457 {@code application/problem+json}, so an edge rejection looks like every other
 * error this platform returns rather than Spring Security's default empty-bodied challenge.
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
        // Why the token failed is logged, not returned: telling a caller whether a forged token
        // failed on signature or on expiry tells them which half to fix.
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
