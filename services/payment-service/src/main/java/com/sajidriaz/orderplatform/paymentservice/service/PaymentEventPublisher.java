package com.sajidriaz.orderplatform.paymentservice.service;

import com.sajidriaz.orderplatform.common.messaging.MessageTypes;
import com.sajidriaz.orderplatform.common.messaging.PlatformTopics;
import com.sajidriaz.orderplatform.common.money.Money;
import com.sajidriaz.orderplatform.events.MessageKind;
import com.sajidriaz.orderplatform.events.payment.PaymentAuthorized;
import com.sajidriaz.orderplatform.events.payment.PaymentCaptureFailed;
import com.sajidriaz.orderplatform.events.payment.PaymentCaptureUnknown;
import com.sajidriaz.orderplatform.events.payment.PaymentCaptured;
import com.sajidriaz.orderplatform.events.payment.PaymentDeclined;
import com.sajidriaz.orderplatform.events.payment.PaymentRefunded;
import com.sajidriaz.orderplatform.paymentservice.entity.PaymentOperationEntity;
import com.sajidriaz.orderplatform.paymentservice.messaging.OutboxWriter;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Builds and appends payment reply events to the outbox.
 *
 * <p>Separated from {@code PaymentService} so the payment state machine reads as decisions rather
 * than as Avro builder calls, and so every event is constructed in exactly one place — the fields
 * the orchestrator and the audit trail depend on cannot then be populated inconsistently between
 * the direct path and the reconciliation path.
 *
 * <p>All events are keyed by order id: it is the Kafka partition key and the only identifier the
 * orchestrator uses to correlate a reply with its saga.
 */
@Component
public class PaymentEventPublisher {

    private final OutboxWriter outboxWriter;

    public PaymentEventPublisher(OutboxWriter outboxWriter) {
        this.outboxWriter = outboxWriter;
    }

    public void authorized(PaymentOperationEntity operation, UUID correlationId, UUID causationId) {
        UUID orderId = orderIdOf(operation);
        Money amount = operation.getAmount();
        PaymentAuthorized payload = PaymentAuthorized.newBuilder()
                .setOrderId(orderId)
                .setPaymentIntentId(intentIdOf(operation))
                .setPaymentAttemptId(operation.getAttempt().getId())
                .setPaymentOperationId(operation.getId())
                .setAmount(avroMoney(amount))
                .setAuthorizedAt(Instant.now())
                .build();
        append(MessageTypes.EVENT_PAYMENT_AUTHORIZED, PlatformTopics.EVENTS_PAYMENT_AUTHORIZED,
                correlationId, causationId, orderId, payload);
    }

    public void declined(PaymentOperationEntity operation, UUID correlationId, UUID causationId) {
        UUID orderId = orderIdOf(operation);
        PaymentDeclined payload = PaymentDeclined.newBuilder()
                .setOrderId(orderId)
                .setPaymentIntentId(intentIdOf(operation))
                .setPaymentAttemptId(operation.getAttempt().getId())
                .setDeclinedAt(Instant.now())
                .build();
        append(MessageTypes.EVENT_PAYMENT_DECLINED, PlatformTopics.EVENTS_PAYMENT_DECLINED,
                correlationId, causationId, orderId, payload);
    }

    /** The saga pivot: after this, the saga rolls forward rather than compensating. */
    public void captured(PaymentOperationEntity operation, UUID correlationId, UUID causationId) {
        UUID orderId = orderIdOf(operation);
        PaymentCaptured payload = PaymentCaptured.newBuilder()
                .setOrderId(orderId)
                .setPaymentIntentId(intentIdOf(operation))
                .setPaymentAttemptId(operation.getAttempt().getId())
                .setPaymentOperationId(operation.getId())
                // The provider's reference is what makes this capture reconcilable later; a capture
                // event without it would be a fact nobody could verify against the provider.
                .setProviderReference(operation.getProviderReference() == null
                        ? "unknown" : operation.getProviderReference())
                .setAmount(avroMoney(operation.getAmount()))
                .setCapturedAt(Instant.now())
                .build();
        append(MessageTypes.EVENT_PAYMENT_CAPTURED, PlatformTopics.EVENTS_PAYMENT_CAPTURED,
                correlationId, causationId, orderId, payload);
    }

    public void captureFailed(PaymentOperationEntity operation, UUID correlationId, UUID causationId) {
        UUID orderId = orderIdOf(operation);
        PaymentCaptureFailed payload = PaymentCaptureFailed.newBuilder()
                .setOrderId(orderId)
                .setPaymentIntentId(intentIdOf(operation))
                .setPaymentAttemptId(operation.getAttempt().getId())
                .setFailedAt(Instant.now())
                .build();
        append(MessageTypes.EVENT_PAYMENT_CAPTURE_FAILED, PlatformTopics.EVENTS_PAYMENT_CAPTURE_FAILED,
                correlationId, causationId, orderId, payload);
    }

    /**
     * The capture outcome is genuinely unknown (ADR-0016 §2, scenario S-17).
     *
     * <p>Published as an explicit fact rather than left as silence. Silence is indistinguishable
     * from a lost message, and the difference matters: this says "money may have moved and we do not
     * yet know", which is exactly what an operator or a reconciliation dashboard needs to see. The
     * orchestrator does not consume this topic today — its capture deadline only logs — so emitting
     * it is additive and changes no existing behaviour.
     */
    public void captureUnknown(PaymentOperationEntity operation, UUID correlationId, UUID causationId) {
        UUID orderId = orderIdOf(operation);
        PaymentCaptureUnknown payload = PaymentCaptureUnknown.newBuilder()
                .setOrderId(orderId)
                .setPaymentIntentId(intentIdOf(operation))
                .setPaymentAttemptId(operation.getAttempt().getId())
                .setPaymentOperationId(operation.getId())
                .setAmount(avroMoney(operation.getAmount()))
                .setProviderReference(operation.getProviderReference())
                .setDetectedAt(Instant.now())
                .build();
        append(MessageTypes.EVENT_PAYMENT_CAPTURE_UNKNOWN, PlatformTopics.EVENTS_PAYMENT_CAPTURE_UNKNOWN,
                correlationId, causationId, orderId, payload);
    }

    public void refunded(PaymentOperationEntity refundOperation, UUID correlationId, UUID causationId) {
        UUID orderId = orderIdOf(refundOperation);
        PaymentRefunded payload = PaymentRefunded.newBuilder()
                .setOrderId(orderId)
                .setPaymentIntentId(intentIdOf(refundOperation))
                .setPaymentOperationId(refundOperation.getId())
                .setAmount(avroMoney(refundOperation.getAmount()))
                .setRefundedAt(Instant.now())
                .build();
        append(MessageTypes.EVENT_PAYMENT_REFUNDED, PlatformTopics.EVENTS_PAYMENT_REFUNDED,
                correlationId, causationId, orderId, payload);
    }

    private void append(String type, String topic, UUID correlationId, UUID causationId, UUID orderId,
                        org.apache.avro.specific.SpecificRecordBase payload) {
        outboxWriter.append(MessageKind.EVENT, type, topic, correlationId, causationId, orderId, payload);
    }

    private UUID orderIdOf(PaymentOperationEntity operation) {
        return operation.getAttempt().getIntent().getOrderId();
    }

    private UUID intentIdOf(PaymentOperationEntity operation) {
        return operation.getAttempt().getIntent().getId();
    }

    /** Domain Money to its wire form. Integer minor units throughout, never floating point. */
    private com.sajidriaz.orderplatform.common.Money avroMoney(Money amount) {
        return com.sajidriaz.orderplatform.common.Money.newBuilder()
                .setMinorUnits(amount.minorUnits())
                .setCurrency(amount.currency())
                .build();
    }
}
