package com.sajidriaz.orderplatform.common.messaging;

import com.sajidriaz.orderplatform.common.observability.TraceparentContext;
import com.sajidriaz.orderplatform.events.Envelope;
import com.sajidriaz.orderplatform.events.MessageKind;
import com.sajidriaz.orderplatform.events.inventory.StockReserved;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class EnvelopeCodecTest {

    private final EnvelopeCodec codec = new EnvelopeCodec();

    @AfterEach
    void clearContext() {
        TraceparentContext.clear();
    }

    @Test
    void envelopeAndPayloadRoundTrip() {
        UUID orderId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        UUID causationId = UUID.randomUUID();
        Instant reservedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        StockReserved payload = StockReserved.newBuilder()
                .setOrderId(orderId)
                .setReservationId(reservationId)
                .setReservedAt(reservedAt)
                .setExpiresAt(reservedAt.plusSeconds(900))
                .build();

        byte[] bytes = codec.encodeEnvelope(MessageKind.EVENT, MessageTypes.EVENT_INVENTORY_RESERVED,
                correlationId, causationId, orderId, payload);

        Envelope decoded = codec.decodeEnvelope(bytes);
        assertEquals(MessageKind.EVENT, decoded.getMessageKind());
        assertEquals(MessageTypes.EVENT_INVENTORY_RESERVED, decoded.getType());
        assertEquals(correlationId, decoded.getCorrelationId());
        assertEquals(causationId, decoded.getCausationId());
        // aggregateId is the Kafka partition key and the orchestrator's only correlation
        // handle on a reply, so it must be the order id verbatim.
        assertEquals(orderId.toString(), decoded.getAggregateId());
        assertEquals(1, decoded.getSchemaVersion());
        assertEquals("default", decoded.getTenantId());

        StockReserved decodedPayload = codec.decodePayload(decoded, StockReserved.class);
        assertEquals(orderId, decodedPayload.getOrderId());
        assertEquals(reservationId, decodedPayload.getReservationId());
        assertEquals(reservedAt, decodedPayload.getReservedAt());
    }

    @Test
    void traceparentIsAdoptedFromAmbientContextAndIsNullWhenAbsent() {
        UUID orderId = UUID.randomUUID();
        StockReserved payload = stockReserved(orderId);

        assertNull(codec.decodeEnvelope(
                codec.encodeEnvelope(MessageKind.EVENT, MessageTypes.EVENT_INVENTORY_RESERVED,
                        UUID.randomUUID(), null, orderId, payload)).getTraceparent(),
                "no inbound traceparent must stay null, never be fabricated");

        String traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        TraceparentContext.set(traceparent);
        Envelope withTrace = codec.decodeEnvelope(
                codec.encodeEnvelope(MessageKind.EVENT, MessageTypes.EVENT_INVENTORY_RESERVED,
                        UUID.randomUUID(), null, orderId, payload));
        assertEquals(traceparent, withTrace.getTraceparent());
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", TraceparentContext.traceIdOf(traceparent));
    }

    @Test
    void messageIdIsFreshPerEncodeButCanBePinnedForResends() {
        UUID orderId = UUID.randomUUID();
        StockReserved payload = stockReserved(orderId);

        UUID first = codec.decodeEnvelope(codec.encodeEnvelope(MessageKind.EVENT,
                MessageTypes.EVENT_INVENTORY_RESERVED, UUID.randomUUID(), null, orderId, payload)).getMessageId();
        UUID second = codec.decodeEnvelope(codec.encodeEnvelope(MessageKind.EVENT,
                MessageTypes.EVENT_INVENTORY_RESERVED, UUID.randomUUID(), null, orderId, payload)).getMessageId();
        assertNotEquals(first, second);

        // Consumer dedup keys on (messageId, consumerGroup), so a resend of the same logical
        // fact must be able to reuse the id or it would be applied twice downstream.
        UUID pinned = UUID.randomUUID();
        Envelope resent = codec.decodeEnvelope(codec.encodeEnvelope(pinned, MessageKind.EVENT,
                MessageTypes.EVENT_INVENTORY_RESERVED, UUID.randomUUID(), null, orderId, payload, null));
        assertEquals(pinned, resent.getMessageId());
    }

    @Test
    void malformedTraceparentYieldsNullTraceIdRatherThanThrowing() {
        assertNull(TraceparentContext.traceIdOf(null));
        assertNull(TraceparentContext.traceIdOf("garbage"));
        assertNull(TraceparentContext.traceIdOf("00--spanid-01"));
    }

    private StockReserved stockReserved(UUID orderId) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        return StockReserved.newBuilder()
                .setOrderId(orderId)
                .setReservationId(UUID.randomUUID())
                .setReservedAt(now)
                .setExpiresAt(now.plusSeconds(900))
                .build();
    }
}
