package com.sajidriaz.orderplatform.orderservice.messaging;

/**
 * Kafka topic taxonomy (ADR-0010): {@code commands.<target-service>.<action>.v<major>}
 * and {@code events.<owning-service>.<fact>.v<major>}. Centralised here so the
 * relay/orchestrator never hand-builds topic strings inline.
 */
public final class Topics
{

    // Events owned by order-service
    public static final String EVENTS_ORDER_CREATED = "events.order.created.v1";
    public static final String EVENTS_ORDER_CONFIRMED = "events.order.confirmed.v1";
    public static final String EVENTS_ORDER_CANCELLED = "events.order.cancelled.v1";

    // Reply events order-service consumes (owned by inventory/payment)
    public static final String EVENTS_INVENTORY_RESERVED = "events.inventory.reserved.v1";
    public static final String EVENTS_INVENTORY_RESERVATION_FAILED = "events.inventory.reservation-failed.v1";
    public static final String EVENTS_INVENTORY_RELEASED = "events.inventory.released.v1";
    public static final String EVENTS_PAYMENT_AUTHORIZED = "events.payment.authorized.v1";
    public static final String EVENTS_PAYMENT_DECLINED = "events.payment.declined.v1";
    public static final String EVENTS_PAYMENT_CAPTURED = "events.payment.captured.v1";
    public static final String EVENTS_PAYMENT_CAPTURE_FAILED = "events.payment.capture-failed.v1";
    public static final String EVENTS_PAYMENT_REFUNDED = "events.payment.refunded.v1";

    // Commands issued by the saga orchestrator (order-service)
    public static final String COMMANDS_INVENTORY_RESERVE = "commands.inventory.reserve.v1";
    public static final String COMMANDS_INVENTORY_RELEASE = "commands.inventory.release.v1";
    public static final String COMMANDS_PAYMENT_AUTHORIZE = "commands.payment.authorize.v1";
    public static final String COMMANDS_PAYMENT_CAPTURE = "commands.payment.capture.v1";
    public static final String COMMANDS_PAYMENT_REFUND = "commands.payment.refund.v1";

    /**
     * Dead-letter topic name for a consumed topic, per ADR-0006's
     * {@code <topic>.<group>.DLT} pattern — one DLQ per consumer group, so a poison
     * message quarantined by order-service never lands in another service's DLQ.
     */
    public static String deadLetterTopicFor(String topic, String consumerGroup)
    {
        return topic + "." + consumerGroup + ".DLT";
    }

    private Topics()
    {
    }
}
