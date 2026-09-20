package com.sajidriaz.orderplatform.common.messaging;

import com.sajidriaz.orderplatform.common.observability.TraceparentContext;
import com.sajidriaz.orderplatform.events.Envelope;
import com.sajidriaz.orderplatform.events.MessageKind;
import org.apache.avro.io.BinaryDecoder;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.avro.specific.SpecificDatumWriter;
import org.apache.avro.specific.SpecificRecordBase;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.UUID;

/**
 * Serializes/deserializes Avro {@link org.apache.avro.specific.SpecificRecord} payloads and
 * the {@link Envelope} that wraps them, to and from plain {@code byte[]}.
 *
 * <p>Wire format, stated precisely because it is positional and unforgiving: the payload
 * record is Avro-binary-encoded, those bytes are placed in {@code Envelope.payload}, and the
 * whole envelope is then Avro-binary-encoded again. That second encoding is what goes on the
 * wire. There is <strong>no</strong> Confluent schema-registry prefix and no Avro
 * single-object-encoding marker (ADR-0010's documented trade-off: {@code payload} is
 * {@code bytes}, so plain binary encoding suffices and the build stays free of the Confluent
 * repository). Because Avro binary is positional with no schema negotiation, every service
 * must be compiled against the same {@code shared/avro-schemas} version.
 *
 * <p>Promoted into common-lib so the orchestrator and both saga participants cannot drift on
 * the wire format. Deliberately a plain class with no Spring annotations — common-lib carries
 * no framework dependency; each service declares it as a {@code @Bean}.
 */
public class EnvelopeCodec {

    /** Envelope fields that are constant platform-wide until multi-tenancy (ADR-0011). */
    private static final int SCHEMA_VERSION = 1;
    private static final String DEFAULT_TENANT = "default";

    /** Encode a single Avro record to its binary form. */
    public byte[] encodePayload(SpecificRecordBase payload) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            BinaryEncoder encoder = EncoderFactory.get().binaryEncoder(out, null);
            SpecificDatumWriter<SpecificRecordBase> writer = new SpecificDatumWriter<>(payload.getSchema());
            writer.write(payload, encoder);
            encoder.flush();
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to encode Avro payload", e);
        }
    }

    /** Decode a binary Avro payload of a known type. */
    public <T extends SpecificRecordBase> T decodePayload(byte[] bytes, Class<T> type) {
        try {
            T instance = type.getDeclaredConstructor().newInstance();
            BinaryDecoder decoder = DecoderFactory.get().binaryDecoder(bytes, null);
            SpecificDatumReader<T> reader = new SpecificDatumReader<>(instance.getSchema());
            return reader.read(instance, decoder);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot instantiate Avro record " + type, e);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to decode Avro payload", e);
        }
    }

    /** Decode the payload carried inside an already-decoded envelope. */
    public <T extends SpecificRecordBase> T decodePayload(Envelope envelope, Class<T> type) {
        return decodePayload(envelope.getPayload().array(), type);
    }

    /**
     * Build a new {@link Envelope} carrying {@code payload} and encode it ready for the Kafka
     * producer. The traceparent is taken from the ambient {@link TraceparentContext}.
     *
     * @param kind          COMMAND or EVENT (ADR-0010)
     * @param type          fully-qualified message type, e.g. {@code events.inventory.reserved}
     * @param correlationId ties every message of one business flow (one order) together
     * @param causationId   the message that directly caused this one; null for a flow's first
     * @param aggregateId   the order id — becomes the Kafka partition key
     */
    public byte[] encodeEnvelope(MessageKind kind, String type, UUID correlationId, UUID causationId,
                                 UUID aggregateId, SpecificRecordBase payload) {
        return encodeEnvelope(UUID.randomUUID(), kind, type, correlationId, causationId, aggregateId,
                payload, TraceparentContext.current());
    }

    /**
     * Full-control overload. An explicit {@code messageId} matters for resends: consumer
     * dedup keys on {@code (messageId, consumerGroup)} (ADR-0005), so a logically identical
     * message re-published later must carry the <em>same</em> messageId to be recognised as a
     * duplicate rather than applied twice.
     */
    public byte[] encodeEnvelope(UUID messageId, MessageKind kind, String type, UUID correlationId,
                                 UUID causationId, UUID aggregateId, SpecificRecordBase payload,
                                 String traceparent) {
        Envelope envelope = Envelope.newBuilder()
                .setMessageId(messageId)
                .setMessageKind(kind)
                .setCorrelationId(correlationId)
                .setCausationId(causationId)
                .setOccurredAt(Instant.now())
                .setType(type)
                .setSchemaVersion(SCHEMA_VERSION)
                .setTenantId(DEFAULT_TENANT)
                .setAggregateId(aggregateId.toString())
                .setTraceparent(traceparent)
                .setPayload(ByteBuffer.wrap(encodePayload(payload)))
                .build();
        return encodeEnvelopeRecord(envelope);
    }

    private byte[] encodeEnvelopeRecord(Envelope envelope) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            BinaryEncoder encoder = EncoderFactory.get().binaryEncoder(out, null);
            SpecificDatumWriter<Envelope> writer = new SpecificDatumWriter<>(Envelope.getClassSchema());
            writer.write(envelope, encoder);
            encoder.flush();
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to encode Envelope", e);
        }
    }

    /** Decode a raw Kafka record value back into an {@link Envelope}. */
    public Envelope decodeEnvelope(byte[] bytes) {
        try {
            BinaryDecoder decoder = DecoderFactory.get().binaryDecoder(new ByteArrayInputStream(bytes), null);
            SpecificDatumReader<Envelope> reader = new SpecificDatumReader<>(Envelope.getClassSchema());
            return reader.read(null, decoder);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to decode Envelope", e);
        }
    }
}
