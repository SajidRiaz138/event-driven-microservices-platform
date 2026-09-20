package com.sajidriaz.orderplatform.common.messaging;

/**
 * Values for the Avro {@code Envelope.type} field: the fully-qualified message type.
 *
 * <p>Note the deliberate difference from {@link PlatformTopics}: the envelope {@code type}
 * carries <em>no</em> {@code .v1} suffix (the major version lives in
 * {@code Envelope.schemaVersion} and in the topic name), matching the strings
 * order-service already publishes.
 */
public final class MessageTypes {

    public static final String EVENT_ORDER_CREATED = "events.order.created";
    public static final String EVENT_ORDER_CONFIRMED = "events.order.confirmed";
    public static final String EVENT_ORDER_CANCELLED = "events.order.cancelled";

    public static final String EVENT_INVENTORY_RESERVED = "events.inventory.reserved";
    public static final String EVENT_INVENTORY_RESERVATION_FAILED = "events.inventory.reservation-failed";
    public static final String EVENT_INVENTORY_RELEASED = "events.inventory.released";

    public static final String EVENT_PAYMENT_AUTHORIZED = "events.payment.authorized";
    public static final String EVENT_PAYMENT_DECLINED = "events.payment.declined";
    public static final String EVENT_PAYMENT_CAPTURED = "events.payment.captured";
    public static final String EVENT_PAYMENT_CAPTURE_FAILED = "events.payment.capture-failed";
    public static final String EVENT_PAYMENT_CAPTURE_UNKNOWN = "events.payment.capture-unknown";
    public static final String EVENT_PAYMENT_REFUNDED = "events.payment.refunded";

    public static final String COMMAND_INVENTORY_RESERVE = "commands.inventory.reserve";
    public static final String COMMAND_INVENTORY_RELEASE = "commands.inventory.release";
    public static final String COMMAND_PAYMENT_AUTHORIZE = "commands.payment.authorize";
    public static final String COMMAND_PAYMENT_CAPTURE = "commands.payment.capture";
    public static final String COMMAND_PAYMENT_REFUND = "commands.payment.refund";

    private MessageTypes() {
    }
}
