package com.sajidriaz.orderplatform.orderservice.web;

/**
 * Thrown on synchronous validation failures the framework's own bean-validation
 * cannot express directly (e.g. unknown SKU / no server-side price available).
 * Maps to {@code 422 Unprocessable Entity} (REST-API-GUIDE §2).
 */
public class OrderValidationException extends RuntimeException
{

    public OrderValidationException(String message)
    {
        super(message);
    }
}
