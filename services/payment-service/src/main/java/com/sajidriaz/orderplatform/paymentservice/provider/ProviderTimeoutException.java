package com.sajidriaz.orderplatform.paymentservice.provider;

/**
 * Indicates that the provider did not return a definitive response, so the outcome of the
 * operation is unknown.
 *
 * <p>This is a distinct exception type so that it cannot be handled like an ordinary failure.
 * Recording {@code FAILED} could result in incorrect compensation if the operation actually
 * succeeded, while recording {@code SUCCEEDED} could confirm an operation that never completed.
 * The outcome should therefore be recorded as {@code UNKNOWN} and reconciled according to
 * ADR-0016, Section 2.
 */
public class ProviderTimeoutException extends RuntimeException
{
    private final String providerReference;

    /**
     * Creates an exception for an operation whose provider outcome is unknown.
     *
     * @param message description of the failure
     * @param providerReference reference obtained from the provider before the response was lost;
     *                          may be {@code null}
     */
    public ProviderTimeoutException(String message, String providerReference)
    {
        super(message);
        this.providerReference = providerReference;
    }

    public String getProviderReference()
    {
        return providerReference;
    }
}
