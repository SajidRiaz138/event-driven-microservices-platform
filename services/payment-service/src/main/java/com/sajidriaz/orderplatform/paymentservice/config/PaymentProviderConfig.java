package com.sajidriaz.orderplatform.paymentservice.config;

import com.sajidriaz.orderplatform.paymentservice.provider.PaymentProvider;
import com.sajidriaz.orderplatform.paymentservice.provider.StubPaymentProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Wires the payment provider.
 *
 * <p>Phase 1 has no real provider, so a stub stands in — behind the {@link PaymentProvider}
 * interface, and declared {@code @ConditionalOnMissingBean} so a real implementation can replace it
 * without touching any calling code.
 *
 * <p>Its behaviour is selected by the payment-method token, which makes the two failure modes that
 * matter reproducible on demand rather than something to wait for: a decline (scenario S-3) and a
 * capture whose response is lost (scenario S-17), in both variants — funds taken and funds not
 * taken — because the service must not assume which one an UNKNOWN outcome represents.
 */
@Configuration
public class PaymentProviderConfig
{

    private static final Logger log = LoggerFactory.getLogger(PaymentProviderConfig.class);

    @Bean
    @ConditionalOnMissingBean (PaymentProvider.class)
    public PaymentProvider stubPaymentProvider(
                                               @Value ("${payment.provider.stub.decline-tokens:pi_decline}") List<String> declineTokens,
                                               @Value ("${payment.provider.stub.capture-timeout-captured-tokens:pi_capture_timeout_captured}") List<
                                                       String> captureTimeoutCapturedTokens,
                                               @Value ("${payment.provider.stub.capture-timeout-lost-tokens:pi_capture_timeout_lost}") List<
                                                       String> captureTimeoutLostTokens,
                                               @Value ("${payment.provider.stub.capture-timeout-unanswerable-tokens:pi_capture_timeout_unanswerable}") List<
                                                       String> captureTimeoutUnanswerableTokens)
    {
        log.warn("Using the STUB payment provider. No real provider is contacted and no funds move. "
                + "Decline tokens={}, capture-timeout(captured)={}, capture-timeout(lost)={}, "
                + "capture-timeout(unanswerable)={}",
                declineTokens, captureTimeoutCapturedTokens, captureTimeoutLostTokens,
                captureTimeoutUnanswerableTokens);
        return new StubPaymentProvider(declineTokens, captureTimeoutCapturedTokens,
                captureTimeoutLostTokens, captureTimeoutUnanswerableTokens);
    }
}
