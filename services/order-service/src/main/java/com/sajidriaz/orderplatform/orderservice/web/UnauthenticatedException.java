package com.sajidriaz.orderplatform.orderservice.web;

/**
 * Thrown when a request reaches a controller without a validated caller identity — no
 * {@code sub} claim on the {@code Authentication} in the security context. Maps to
 * {@code 401 Unauthorized}.
 *
 * <p>Defence in depth rather than the main path: the security filter chain rejects
 * unauthenticated requests before MVC runs (and renders that 401 through
 * {@code ProblemDetailAuthenticationEntryPoint}), so this is unreachable while the
 * authorization rules are correct. It exists so that an accidentally loosened rule surfaces as
 * a clean 401 instead of a {@code NullPointerException} — or an order attributed to nobody.
 */
public class UnauthenticatedException extends RuntimeException {

    public UnauthenticatedException(String message) {
        super(message);
    }
}
