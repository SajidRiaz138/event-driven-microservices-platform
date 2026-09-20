package com.sajidriaz.orderplatform.orderservice.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Resolves the authenticated caller's customer id from the validated access token.
 *
 * <p>The customer id is the JWT {@code sub} claim of a token <b>this service</b> validated
 * (signature against the issuer's JWKS, issuer, audience, expiry — see
 * {@link SecurityConfig}). It is never read from a request header: a header set by an
 * upstream hop is only as trustworthy as every hop before it, and treating one as identity is
 * the confused-deputy vulnerability ADR-0009 exists to avoid. The gateway therefore forwards
 * the token itself and validates in parallel, rather than flattening identity into a header.
 *
 * <p>This used to read a dev-only {@code X-User-Id} header, because auth-service and the
 * gateway did not exist yet. That stand-in was deliberately isolated behind this one class so
 * the switch to real tokens would touch it and nothing else; that is what happened.
 *
 * <p>By the time a request reaches a controller the security filter chain has already rejected
 * anything unauthenticated, so {@code null} here is unreachable on the authenticated paths.
 * It is still reported rather than assumed away: an authorization rule loosened by accident
 * should surface as a clean 401, not a {@code NullPointerException} — or worse, an order
 * attributed to a caller nobody identified.
 */
@Component
public class CallerIdentityResolver
{

    /**
     * @return the caller's customer id (the token's {@code sub} claim), or {@code null} if the
     *         request carries no validated JWT.
     */
    public String resolve()
    {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuthentication)
        {
            String subject = jwtAuthentication.getToken().getSubject();
            return (subject == null || subject.isBlank()) ? null : subject;
        }
        return null;
    }
}
