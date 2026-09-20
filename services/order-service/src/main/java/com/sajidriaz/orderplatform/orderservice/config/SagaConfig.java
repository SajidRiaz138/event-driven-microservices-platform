package com.sajidriaz.orderplatform.orderservice.config;

import com.sajidriaz.orderplatform.orderservice.saga.SagaTimeouts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Binds the saga's step budgets from configuration (ADR-0003). Previously
 * {@code SagaOrchestrator} hard-coded the reservation TTL as a Java constant while
 * {@code order-platform.saga.reservation-ttl-seconds} sat in application.yml doing
 * nothing — changing the property had no effect. It is now genuinely wired.
 */
@Configuration
public class SagaConfig {

    @Bean
    public SagaTimeouts sagaTimeouts(
            @Value("${order-platform.saga.reservation-ttl-seconds:900}") int reservationTtlSeconds,
            @Value("${order-platform.saga.step-timeout.stock-reservation-seconds:30}") long stockReservationSeconds,
            @Value("${order-platform.saga.step-timeout.payment-authorization-seconds:30}") long paymentAuthorizationSeconds,
            @Value("${order-platform.saga.step-timeout.payment-capture-seconds:30}") long paymentCaptureSeconds) {
        return new SagaTimeouts(
                reservationTtlSeconds,
                Duration.ofSeconds(stockReservationSeconds),
                Duration.ofSeconds(paymentAuthorizationSeconds),
                Duration.ofSeconds(paymentCaptureSeconds));
    }
}
