package com.sajidriaz.orderplatform.paymentservice.messaging;

import com.sajidriaz.orderplatform.commands.payment.AuthorizePayment;
import com.sajidriaz.orderplatform.commands.payment.RefundPayment;
import com.sajidriaz.orderplatform.common.messaging.EnvelopeCodec;
import com.sajidriaz.orderplatform.common.messaging.PlatformTopics;
import com.sajidriaz.orderplatform.common.money.Money;
import com.sajidriaz.orderplatform.common.observability.CorrelationContext;
import com.sajidriaz.orderplatform.common.observability.TraceparentContext;
import com.sajidriaz.orderplatform.events.Envelope;
import com.sajidriaz.orderplatform.paymentservice.entity.ProcessedMessageEntity;
import com.sajidriaz.orderplatform.paymentservice.repository.ProcessedMessageRepository;
import com.sajidriaz.orderplatform.paymentservice.service.PaymentService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Consumes the saga's payment commands and replies through the outbox.
 *
 * <p>One listener over all three topics rather than one each: every {@code @KafkaListener} joins the
 * consumer group separately, and three group rebalances at startup buy nothing.
 *
 * <p>The offset is acknowledged only after the transaction containing both the payment state change
 * and the {@code processed_message} row has committed (ADR-0005). A crash before the ack causes
 * redelivery, which the dedup row makes harmless — important here beyond the usual reasons, because
 * re-applying a payment command is how systems charge twice.
 */
@Component
public class PaymentCommandListener
{

    private static final Logger log = LoggerFactory.getLogger(PaymentCommandListener.class);

    static final String CONSUMER_GROUP = "payment-service";

    private final PaymentService paymentService;
    private final ProcessedMessageRepository processedMessageRepository;
    private final EnvelopeCodec envelopeCodec;

    public PaymentCommandListener(PaymentService paymentService,
                                  ProcessedMessageRepository processedMessageRepository,
                                  EnvelopeCodec envelopeCodec)
    {
        this.paymentService = paymentService;
        this.processedMessageRepository = processedMessageRepository;
        this.envelopeCodec = envelopeCodec;
    }

    @KafkaListener (topics = {
                               PlatformTopics.COMMANDS_PAYMENT_AUTHORIZE,
                               PlatformTopics.COMMANDS_PAYMENT_CAPTURE,
                               PlatformTopics.COMMANDS_PAYMENT_REFUND
    }, groupId = CONSUMER_GROUP)
    public void onCommand(ConsumerRecord<String, byte[]> record, Acknowledgment ack)
    {
        Envelope envelope = envelopeCodec.decodeEnvelope(record.value());
        // Adopt the sender's context so logs join up and events produced here carry the same
        // traceparent (ADR-0013). Cleared in the finally: a virtual thread must never be left
        // holding stale context.
        CorrelationContext.adoptOrGenerate(String.valueOf(envelope.getCorrelationId()));
        TraceparentContext.set(envelope.getTraceparent());
        CorrelationContext.setTraceId(TraceparentContext.traceIdOf(envelope.getTraceparent()));
        CorrelationContext.setTenant(envelope.getTenantId());
        try
        {
            applyIfNotProcessed(envelope, e -> dispatch(record.topic(), e));
            ack.acknowledge();
        }
        finally
        {
            TraceparentContext.clear();
            CorrelationContext.clear();
        }
    }

    private void dispatch(String topic, Envelope envelope)
    {
        UUID orderId = orderIdOf(envelope);
        UUID causationId = envelope.getMessageId();
        UUID correlationId = envelope.getCorrelationId();

        if (PlatformTopics.COMMANDS_PAYMENT_AUTHORIZE.equals(topic))
        {
            AuthorizePayment command = envelopeCodec.decodePayload(envelope, AuthorizePayment.class);
            paymentService.authorize(new PaymentService.AuthorizeCommand(
                    orderId,
                    command.getCustomerId(),
                    command.getPaymentIntentId(),
                    command.getPaymentAttemptId(),
                    money(command.getAmount()),
                    command.getPaymentMethodToken()), correlationId, causationId);
        }
        else if (PlatformTopics.COMMANDS_PAYMENT_CAPTURE.equals(topic))
        {
            // The command's intent/attempt ids are deliberately not used to find the authorization:
            // the orchestrator mints fresh ones per command, so they would match nothing. The order
            // id is the identifier that holds across the flow.
            paymentService.capture(orderId, correlationId, causationId);
        }
        else if (PlatformTopics.COMMANDS_PAYMENT_REFUND.equals(topic))
        {
            RefundPayment command = envelopeCodec.decodePayload(envelope, RefundPayment.class);
            paymentService.refund(new PaymentService.RefundCommand(
                    orderId,
                    command.getPaymentIntentId(),
                    command.getPaymentOperationId(),
                    money(command.getAmount())), correlationId, causationId);
        }
        else
        {
            log.warn("No handler mapped for topic {}", topic);
        }
    }

    /**
     * Runs {@code action} unless this message was already processed by this consumer group, and
     * records it as processed — both in ONE transaction with the payment state change (ADR-0005). A
     * separate transaction for the marker would leave a window where a charge is applied but not
     * recorded as processed, and the redelivery would charge again.
     */
    @Transactional
    void applyIfNotProcessed(Envelope envelope, Consumer<Envelope> action)
    {
        UUID messageId = envelope.getMessageId();
        var key = new ProcessedMessageEntity.Key(messageId, CONSUMER_GROUP);
        if (processedMessageRepository.existsById(key))
        {
            log.info("Skipping already-processed message {} (type={})", messageId, envelope.getType());
            return;
        }
        action.accept(envelope);
        processedMessageRepository.save(new ProcessedMessageEntity(messageId, CONSUMER_GROUP));
    }

    private Money money(com.sajidriaz.orderplatform.common.Money wire)
    {
        return Money.of(wire.getMinorUnits(), wire.getCurrency());
    }

    /**
     * The order id, from the envelope's {@code aggregateId}. A malformed value throws
     * {@link IllegalArgumentException}, classified as permanent by the error handler: no amount of
     * retrying will make it parse, so it goes straight to the DLQ.
     */
    private UUID orderIdOf(Envelope envelope)
    {
        return UUID.fromString(envelope.getAggregateId());
    }
}
