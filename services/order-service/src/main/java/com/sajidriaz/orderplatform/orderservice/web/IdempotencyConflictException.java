package com.sajidriaz.orderplatform.orderservice.web;

/**
 * Thrown when an {@code Idempotency-Key} is reused with a request whose hash differs
 * from the original request (ADR-0005, S-18). Maps to {@code 409 Conflict}.
 */
public class IdempotencyConflictException extends RuntimeException
{

    public IdempotencyConflictException(String message)
    {
        super(message);
    }
}
