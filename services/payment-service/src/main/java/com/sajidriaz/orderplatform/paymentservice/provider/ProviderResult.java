package com.sajidriaz.orderplatform.paymentservice.provider;

/**
 * A provider's positively-established answer. An ambiguous outcome is never represented here — it
 * is a {@link ProviderTimeoutException}, so it cannot be mistaken for either an approval or a
 * decline.
 *
 * @param approved          whether the provider acted
 * @param providerReference the provider's reference for the operation, used for reconciliation
 * @param failureReason     why it declined; null when approved
 */
public record ProviderResult(boolean approved, String providerReference, String failureReason) {

    public static ProviderResult approved(String providerReference)
    {
        return new ProviderResult(true, providerReference, null);
    }

    public static ProviderResult declined(String reason)
    {
        return new ProviderResult(false, null, reason);
    }
}
