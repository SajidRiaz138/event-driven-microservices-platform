package com.sajidriaz.orderplatform.paymentservice.provider;

/**
 * The provider did not answer in a way that establishes what happened: a timeout, a dropped
 * connection, a crash mid-call. The operation may or may not have taken effect.
 *
 * <p>This is a distinct type precisely so it cannot be handled like an ordinary failure. Catching
 * it and recording {@code FAILED} would risk refunding a capture that never happened; ignoring it
 * and recording {@code SUCCEEDED} would confirm an order nobody paid for. The only correct handling
 * is to record {@code UNKNOWN} and reconcile (ADR-0016 §2).
 *
 * @param providerReference a reference obtained before the response was lost, if any; often null
 */
public class ProviderTimeoutException extends RuntimeException {

    private final String providerReference;

    public ProviderTimeoutException(String message, String providerReference) {
        super(message);
        this.providerReference = providerReference;
    }

    public String getProviderReference() {
        return providerReference;
    }
}
