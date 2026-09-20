package com.sajidriaz.orderplatform.orderservice.web;

import java.util.NoSuchElementException;

/**
 * Thrown when an order does not exist OR is not owned by the caller. Deliberately a
 * single exception type for both cases — the whole point of S-15 is that "missing"
 * and "exists but is someone else's" must be indistinguishable to the caller
 * (404, never 403; existence is not revealed).
 */
public class OrderNotFoundException extends NoSuchElementException {

    public OrderNotFoundException(String message) {
        super(message);
    }
}
