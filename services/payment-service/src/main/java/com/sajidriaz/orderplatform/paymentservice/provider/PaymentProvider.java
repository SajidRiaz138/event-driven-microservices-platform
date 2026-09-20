package com.sajidriaz.orderplatform.paymentservice.provider;

import com.sajidriaz.orderplatform.common.money.Money;

import java.util.Optional;

/**
 * The payment provider, as this service needs it (ADR-0016).
 *
 * <p>Three properties of this interface are deliberate:
 *
 * <ul>
 *   <li>Every call carries a {@code providerIdempotencyKey}. Retries reuse the key, so the provider
 *       collapses them into one effect and a double charge becomes structurally impossible.</li>
 *   <li>An ambiguous outcome is signalled by {@link ProviderTimeoutException}, not by a result
 *       value. There is no way for a caller to accidentally read a lost response as either success
 *       or failure — the only thing it can do is record UNKNOWN.</li>
 *   <li>{@link #lookup} exists so an UNKNOWN outcome can be resolved by asking, rather than by
 *       retrying blindly or guessing. This is the reconciliation path.</li>
 * </ul>
 */
public interface PaymentProvider
{

    /**
     * Place a hold on funds.
     *
     * @throws ProviderTimeoutException when the outcome is genuinely unknown
     */
    ProviderResult authorize(ProviderCall call);

    /**
     * Take previously held funds.
     *
     * @throws ProviderTimeoutException when the outcome is genuinely unknown — the funds may or may
     *         not have moved, which is the dangerous case ADR-0016 exists for
     */
    ProviderResult capture(ProviderCall call);

    /**
     * Return captured funds.
     *
     * @throws ProviderTimeoutException when the outcome is genuinely unknown
     */
    ProviderResult refund(ProviderCall call);

    /**
     * Ask the provider what actually happened for an idempotency key, to resolve an UNKNOWN
     * operation. An empty result means the provider still cannot say — the operation must stay
     * UNKNOWN rather than being assumed either way.
     */
    Optional<ProviderResult> lookup(String providerIdempotencyKey);

    /**
     * One provider call.
     *
     * @param providerIdempotencyKey stable across every retry of this logical operation
     * @param paymentMethodToken     opaque provider token; never card data
     * @param amount                 integer minor units, never floating point
     */
    record ProviderCall(String providerIdempotencyKey, String paymentMethodToken, Money amount) {
    }
}
