package com.sajidriaz.orderplatform.common.messaging;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the promoted topic constants to their literal strings. These are wire contracts
 * shared with order-service (which keeps its own {@code Topics} class): if someone
 * "tidies" a constant here, the participants would publish to a topic the orchestrator
 * never consumes — a silent failure that no compiler catches. This test is the guard.
 */
class PlatformTopicsTest {

    @Test
    void topicNamesFollowTheAdr0010Grammar() {
        assertEquals("commands.inventory.reserve.v1", PlatformTopics.COMMANDS_INVENTORY_RESERVE);
        assertEquals("commands.inventory.release.v1", PlatformTopics.COMMANDS_INVENTORY_RELEASE);
        assertEquals("commands.payment.authorize.v1", PlatformTopics.COMMANDS_PAYMENT_AUTHORIZE);
        assertEquals("commands.payment.capture.v1", PlatformTopics.COMMANDS_PAYMENT_CAPTURE);
        assertEquals("commands.payment.refund.v1", PlatformTopics.COMMANDS_PAYMENT_REFUND);

        assertEquals("events.inventory.reserved.v1", PlatformTopics.EVENTS_INVENTORY_RESERVED);
        assertEquals("events.inventory.reservation-failed.v1", PlatformTopics.EVENTS_INVENTORY_RESERVATION_FAILED);
        assertEquals("events.inventory.released.v1", PlatformTopics.EVENTS_INVENTORY_RELEASED);

        assertEquals("events.payment.authorized.v1", PlatformTopics.EVENTS_PAYMENT_AUTHORIZED);
        assertEquals("events.payment.declined.v1", PlatformTopics.EVENTS_PAYMENT_DECLINED);
        assertEquals("events.payment.captured.v1", PlatformTopics.EVENTS_PAYMENT_CAPTURED);
        assertEquals("events.payment.capture-failed.v1", PlatformTopics.EVENTS_PAYMENT_CAPTURE_FAILED);
        assertEquals("events.payment.capture-unknown.v1", PlatformTopics.EVENTS_PAYMENT_CAPTURE_UNKNOWN);
        assertEquals("events.payment.refunded.v1", PlatformTopics.EVENTS_PAYMENT_REFUNDED);
    }

    @Test
    void deadLetterTopicFollowsPerConsumerGroupPattern() {
        assertEquals("commands.inventory.reserve.v1.inventory-service.DLT",
                PlatformTopics.deadLetterTopicFor(PlatformTopics.COMMANDS_INVENTORY_RESERVE, "inventory-service"));
        assertEquals("commands.payment.capture.v1.payment-service.DLT",
                PlatformTopics.deadLetterTopicFor(PlatformTopics.COMMANDS_PAYMENT_CAPTURE, "payment-service"));
    }

    @Test
    void envelopeTypeCarriesNoVersionSuffix() {
        // The major version lives in the topic name and Envelope.schemaVersion, never here.
        assertEquals("events.inventory.reserved", MessageTypes.EVENT_INVENTORY_RESERVED);
        assertEquals("events.payment.captured", MessageTypes.EVENT_PAYMENT_CAPTURED);
        assertEquals("commands.payment.authorize", MessageTypes.COMMAND_PAYMENT_AUTHORIZE);
    }
}
