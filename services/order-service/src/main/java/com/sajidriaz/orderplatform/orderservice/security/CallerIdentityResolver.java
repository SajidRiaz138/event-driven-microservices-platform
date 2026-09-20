package com.sajidriaz.orderplatform.orderservice.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Resolves the authenticated caller's customer id.
 *
 * <p><b>TODO(ADR-0009):</b> auth-service and the API gateway are not built yet
 * (Phase 1, see docs/BUILD-LOG.md). In production, every service — including this
 * one — is its own OAuth2 resource server: the customer id MUST come from the
 * {@code sub} claim of a JWT that THIS service independently validates (signature
 * against the issuer's JWKS, issuer, audience, expiry) — never from a header supplied
 * by an upstream hop, which would be a confused-deputy vulnerability (ADR-0009).
 *
 * <p>Until the resource-server config exists, this class reads a plain
 * {@code X-User-Id} header as a DEV-ONLY stand-in so the saga can be exercised end to
 * end. It is intentionally isolated behind this single class so swapping it for real
 * JWT validation later touches one file, not the controller or service layer.
 */
@Component
public class CallerIdentityResolver {

    private final String devUserIdHeader;

    public CallerIdentityResolver(@Value("${order-platform.security.dev-user-id-header:X-User-Id}") String devUserIdHeader) {
        this.devUserIdHeader = devUserIdHeader;
    }

    /**
     * @return the caller's customer id, or {@code null} if unauthenticated.
     */
    public String resolve(HttpServletRequest request) {
        // TODO(ADR-0009): replace with SecurityContextHolder / Authentication#getName()
        // once this service is wired as an OAuth2 resource server; the JWT `sub` claim
        // becomes the customer id, and this header is removed entirely.
        String headerValue = request.getHeader(devUserIdHeader);
        return (headerValue == null || headerValue.isBlank()) ? null : headerValue;
    }
}
