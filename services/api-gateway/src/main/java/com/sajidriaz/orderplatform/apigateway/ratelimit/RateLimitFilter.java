package com.sajidriaz.orderplatform.apigateway.ratelimit;

import com.sajidriaz.orderplatform.common.observability.CorrelationContext;
import com.sajidriaz.orderplatform.common.web.ProblemJson;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Enforces the per-client request budget, answering {@code 429 Too Many Requests} with
 * {@code Retry-After} (REST-API-GUIDE §1).
 *
 * <p>Clients are keyed by the token's {@code sub} when the request is authenticated, and by
 * source address otherwise. Keying on identity rather than address matters both ways: many
 * customers can share one NAT address (so address-only limiting would throttle innocent users
 * because of a noisy neighbour), and one client can arrive from many addresses (so it could
 * evade an address-only limit entirely). The address key still covers the unauthenticated
 * routes — notably token issuance, where limiting is the point.
 *
 * <p>Registered inside the security filter chain, after authentication, which is what makes the
 * {@code sub} key available. The cost is that rate limiting happens after signature
 * verification; for a bearer-token API that is the right order, since the alternative is
 * throttling requests before knowing who they belong to.
 *
 * <p>Advertises {@code X-RateLimit-Limit} and {@code X-RateLimit-Remaining} on every response so
 * a well-behaved client can pace itself instead of discovering the limit by being refused.
 */
public class RateLimitFilter extends OncePerRequestFilter
{

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final TokenBucketRateLimiter rateLimiter;

    public RateLimitFilter(TokenBucketRateLimiter rateLimiter)
    {
        this.rateLimiter = rateLimiter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException
    {
        String clientKey = clientKey(request);
        TokenBucketRateLimiter.Decision decision = rateLimiter.tryConsume(clientKey);

        response.setHeader("X-RateLimit-Limit", String.valueOf(decision.limit()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(decision.remaining()));

        if (decision.allowed())
        {
            filterChain.doFilter(request, response);
            return;
        }

        log.info("Rate limit exceeded for client {} on {} {}", clientKey, request.getMethod(),
                request.getRequestURI());
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(decision.retryAfterSeconds()));
        response.setContentType(ProblemJson.CONTENT_TYPE);
        response.getWriter()
                .write(ProblemJson.render(
                        HttpStatus.TOO_MANY_REQUESTS.value(),
                        "rate-limit-exceeded",
                        "Too many requests",
                        "The client's request quota is exhausted; retry after "
                                + decision.retryAfterSeconds() + " second(s).",
                        request.getRequestURI(),
                        CorrelationContext.currentCorrelationId()));
    }

    private String clientKey(HttpServletRequest request)
    {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuthentication)
        {
            return "sub:" + jwtAuthentication.getToken().getSubject();
        }
        return "ip:" + request.getRemoteAddr();
    }
}
