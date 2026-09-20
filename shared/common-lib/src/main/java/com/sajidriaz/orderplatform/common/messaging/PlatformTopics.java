package com.sajidriaz.orderplatform.common.messaging;

/**
 * Platform-wide Kafka topic taxonomy (ADR-0010/ADR-0017):
 * {@code commands.<target-service>.<action>.v<major>} and
 * {@code events.<owning-service>.<fact>.v<major>}.
 *
 * <p>Promoted here so that the saga participants (payment-service, inventory-service) and
 * the orchestrator cannot drift apart on a topic string — a typo would not fail the build,
 * it would silently create a new topic that nobody consumes. order-service keeps its own
 * {@code Topics} class (it predates this one and is deliberately left untouched); the
 * constants below are asserted string-for-string in {@code PlatformTopicsTest} so the two
 * cannot diverge without a test failing.
 *
 * <p>Deliberately framework-free: no Spring, no Kafka, no JPA — common-lib stays a
 * lightweight contracts module.
 */
public final class PlatformTopics
{

    // Events owned by order-service
    public static final String EVENTS_ORDER_CREATED = "events.order.created.v1";
    public static final String EVENTS_ORDER_CONFIRMED = "events.order.confirmed.v1";
    public static final String EVENTS_ORDER_CANCELLED = "events.order.cancelled.v1";

    // Events owned by inventory-service
    public static final String EVENTS_INVENTORY_RESERVED = "events.inventory.reserved.v1";
    public static final String EVENTS_INVENTORY_RESERVATION_FAILED = "events.inventory.reservation-failed.v1";
    public static final String EVENTS_INVENTORY_RELEASED = "events.inventory.released.v1";

    // Events owned by payment-service
    public static final String EVENTS_PAYMENT_AUTHORIZED = "events.payment.authorized.v1";
    public static final String EVENTS_PAYMENT_DECLINED = "events.payment.declined.v1";
    public static final String EVENTS_PAYMENT_CAPTURED = "events.payment.captured.v1";
    public static final String EVENTS_PAYMENT_CAPTURE_FAILED = "events.payment.capture-failed.v1";
    public static final String EVENTS_PAYMENT_REFUNDED = "events.payment.refunded.v1";
    /**
     * The explicit UNKNOWN-capture signal required by ADR-0016 §2. New in this milestone;
     * order-service does not consume it yet (its capture deadline only logs), so it is
     * additive and breaks nothing — it exists so the ambiguous outcome is a published fact
     * for reconciliation/audit rather than a silence indistinguishable from a lost message.
     */
    public static final String EVENTS_PAYMENT_CAPTURE_UNKNOWN = "events.payment.capture-unknown.v1";

    // Commands issued by the saga orchestrator (order-service)
    public static final String COMMANDS_INVENTORY_RESERVE = "commands.inventory.reserve.v1";
    public static final String COMMANDS_INVENTORY_RELEASE = "commands.inventory.release.v1";
    public static final String COMMANDS_PAYMENT_AUTHORIZE = "commands.payment.authorize.v1";
    public static final String COMMANDS_PAYMENT_CAPTURE = "commands.payment.capture.v1";
    public static final String COMMANDS_PAYMENT_REFUND = "commands.payment.refund.v1";

    /**
     * Dead-letter topic for a consumed topic, per ADR-0006's {@code <topic>.<group>.DLT}
     * pattern — one DLQ per consumer group, so a message quarantined by one service never
     * lands in another service's DLQ.
     */
    public static String deadLetterTopicFor(String topic, String consumerGroup)
    {
        return topic + "." + consumerGroup + ".DLT";
    }

    private PlatformTopics()
    {
    }
}
