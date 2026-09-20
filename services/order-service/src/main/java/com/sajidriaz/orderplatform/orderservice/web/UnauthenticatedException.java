package com.sajidriaz.orderplatform.orderservice.web;

/**
 * Thrown when the caller is not authenticated at all (dev stand-in: no
 * {@code X-User-Id} header present — TODO(ADR-0009) replace with a real missing/invalid
 * JWT check). Maps to {@code 401 Unauthorized}.
 */
public class UnauthenticatedException extends RuntimeException {

    public UnauthenticatedException(String message) {
        super(message);
    }
}
