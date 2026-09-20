package com.sajidriaz.orderplatform.paymentservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * payment-service: a saga participant (ADR-0003). Consumes AuthorizePayment, CapturePayment and
 * RefundPayment commands and replies with payment events, following the operations model and
 * unknown-outcome reconciliation of ADR-0016.
 *
 * <p>{@code @EnableScheduling} is required, not optional: without it the outbox relay never publishes
 * and — worse — the reconciliation worker never runs, so any capture with an UNKNOWN outcome would
 * stay unknown forever with money potentially taken.
 */
@SpringBootApplication
@EnableScheduling
public class PaymentServiceApplication
{

    public static void main(String[] args)
    {
        SpringApplication.run(PaymentServiceApplication.class, args);
    }
}
