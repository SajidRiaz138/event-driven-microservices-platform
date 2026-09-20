package com.sajidriaz.orderplatform.orderservice.messaging;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the topic taxonomy that other services and the ops runbooks depend on
 * (ADR-0010 for the command/event grammar, ADR-0006 for the dead-letter pattern).
 */
class TopicsTest {

    @Test
    void deadLetterTopicFollowsPerConsumerGroupPattern() {
        // ADR-0006: DLQ per consumer group, "<topic>.<group>.DLT" — so a poison message
        // quarantined by order-service never lands in another service's dead-letter queue.
        assertThat(Topics.deadLetterTopicFor(Topics.EVENTS_PAYMENT_CAPTURED, "order-service"))
                .isEqualTo("events.payment.captured.v1.order-service.DLT");
    }

    @Test
    void topicNamesFollowAdr0010Grammar() {
        // commands.<target-service>.<action>.v<major> / events.<owning-service>.<fact>.v<major>
        assertThat(Topics.COMMANDS_INVENTORY_RESERVE).isEqualTo("commands.inventory.reserve.v1");
        assertThat(Topics.EVENTS_ORDER_CREATED).isEqualTo("events.order.created.v1");
        assertThat(Topics.EVENTS_INVENTORY_RELEASED).isEqualTo("events.inventory.released.v1");
    }
}
