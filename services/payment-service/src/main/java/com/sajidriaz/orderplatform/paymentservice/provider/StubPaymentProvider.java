package com.sajidriaz.orderplatform.paymentservice.provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An in-process stand-in for a real payment provider. There is no external provider in Phase 1, and
 * the failure modes that matter — a decline, and a capture whose response is lost — must be
 * reproducible on demand rather than waited for.
 *
 * <p>Behaviour is driven by the opaque payment-method token, so a test (or the demo script) selects
 * an outcome simply by placing an order with a particular instrument:
 *
 * <ul>
 *   <li>a token in {@code declineTokens} → authorization is declined (scenario S-3)</li>
 *   <li>a token in {@code captureTimeoutCapturedTokens} → capture throws
 *       {@link ProviderTimeoutException} <em>after</em> recording the capture internally: the funds
 *       DID move and the response was lost. Reconciliation must find SUCCEEDED (scenario S-17)</li>
 *   <li>a token in {@code captureTimeoutLostTokens} → capture throws without recording anything:
 *       the funds did NOT move. Reconciliation must find FAILED — the same ambiguous signal
 *       resolving the other way, which is why UNKNOWN cannot be assumed to mean either</li>
 * </ul>
 *
 * <p>Critically, the stub models the one property a real provider must have: <strong>it is
 * idempotent on the idempotency key.</strong> Effects are stored keyed by that key, so a repeated
 * call with the same key returns the original outcome and does not charge twice. That is what the
 * service's retry behaviour relies on, so a stub without it would prove nothing.
 */
public class StubPaymentProvider implements PaymentProvider {

    private static final Logger log = LoggerFactory.getLogger(StubPaymentProvider.class);

    /** Effects the provider has recorded, keyed by idempotency key — the provider's own ledger. */
    private final Map<String, ProviderResult> effects = new ConcurrentHashMap<>();

    /** Keys the provider cannot answer about yet: lookup returns empty, so they stay UNKNOWN. */
    private final Set<String> unanswerableKeys = ConcurrentHashMap.newKeySet();

    private final List<String> declineTokens;
    private final List<String> captureTimeoutCapturedTokens;
    private final List<String> captureTimeoutLostTokens;
    private final List<String> captureTimeoutUnanswerableTokens;

    public StubPaymentProvider(List<String> declineTokens,
                               List<String> captureTimeoutCapturedTokens,
                               List<String> captureTimeoutLostTokens,
                               List<String> captureTimeoutUnanswerableTokens) {
        this.declineTokens = List.copyOf(declineTokens);
        this.captureTimeoutCapturedTokens = List.copyOf(captureTimeoutCapturedTokens);
        this.captureTimeoutLostTokens = List.copyOf(captureTimeoutLostTokens);
        this.captureTimeoutUnanswerableTokens = List.copyOf(captureTimeoutUnanswerableTokens);
    }

    @Override
    public ProviderResult authorize(ProviderCall call) {
        ProviderResult existing = effects.get(call.providerIdempotencyKey());
        if (existing != null) {
            log.info("Provider: authorize replayed from idempotency key {}", call.providerIdempotencyKey());
            return existing;
        }
        if (declineTokens.contains(call.paymentMethodToken())) {
            return record(call, ProviderResult.declined("card_declined"));
        }
        return record(call, ProviderResult.approved("auth_" + shortRef(call)));
    }

    @Override
    public ProviderResult capture(ProviderCall call) {
        ProviderResult existing = effects.get(call.providerIdempotencyKey());
        if (existing != null) {
            // The provider collapsing a retry into the original effect: this is why reusing the
            // idempotency key cannot double-charge, even after an UNKNOWN outcome.
            log.info("Provider: capture replayed from idempotency key {}", call.providerIdempotencyKey());
            return existing;
        }

        if (captureTimeoutCapturedTokens.contains(call.paymentMethodToken())) {
            // The money moves, then the response is lost. The caller cannot tell this apart from
            // the case below — which is exactly the point.
            String reference = "cap_" + shortRef(call);
            effects.put(call.providerIdempotencyKey(), ProviderResult.approved(reference));
            log.warn("Provider: capture succeeded but the response was lost (key {})",
                    call.providerIdempotencyKey());
            throw new ProviderTimeoutException("capture response lost", null);
        }

        if (captureTimeoutLostTokens.contains(call.paymentMethodToken())) {
            log.warn("Provider: capture did not complete and the response was lost (key {})",
                    call.providerIdempotencyKey());
            throw new ProviderTimeoutException("capture response lost", null);
        }

        if (captureTimeoutUnanswerableTokens.contains(call.paymentMethodToken())) {
            // The provider will keep answering "still processing" when asked. The operation must
            // stay UNKNOWN indefinitely rather than being resolved on a guess.
            unanswerableKeys.add(call.providerIdempotencyKey());
            log.warn("Provider: capture outcome is indeterminate and will not resolve (key {})",
                    call.providerIdempotencyKey());
            throw new ProviderTimeoutException("capture outcome indeterminate", null);
        }

        if (declineTokens.contains(call.paymentMethodToken())) {
            return record(call, ProviderResult.declined("capture_declined"));
        }
        return record(call, ProviderResult.approved("cap_" + shortRef(call)));
    }

    @Override
    public ProviderResult refund(ProviderCall call) {
        ProviderResult existing = effects.get(call.providerIdempotencyKey());
        if (existing != null) {
            return existing;
        }
        return record(call, ProviderResult.approved("ref_" + shortRef(call)));
    }

    @Override
    public Optional<ProviderResult> lookup(String providerIdempotencyKey) {
        if (unanswerableKeys.contains(providerIdempotencyKey)) {
            // "Still processing": the provider genuinely cannot say. An empty result is the only
            // honest answer, and the caller must leave the operation UNKNOWN rather than guess.
            return Optional.empty();
        }
        ProviderResult recorded = effects.get(providerIdempotencyKey);
        if (recorded != null) {
            return Optional.of(recorded);
        }
        // The provider has no record of this key at all, which for an idempotency-keyed API means
        // the operation never took effect — a positively established negative, not an unknown.
        return Optional.of(ProviderResult.declined("no_such_operation"));
    }

    private ProviderResult record(ProviderCall call, ProviderResult result) {
        effects.put(call.providerIdempotencyKey(), result);
        return result;
    }

    private String shortRef(ProviderCall call) {
        return Integer.toHexString(call.providerIdempotencyKey().hashCode());
    }
}
