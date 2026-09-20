package com.sajidriaz.orderplatform.orderservice.messaging;

import com.sajidriaz.orderplatform.events.Envelope;
import com.sajidriaz.orderplatform.events.MessageKind;
import com.sajidriaz.orderplatform.orderservice.observability.TraceparentContext;
import org.apache.avro.specific.SpecificRecord;
import org.apache.avro.specific.SpecificRecordBase;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.UUID;

import org.apache.avro.io.BinaryDecoder;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.avro.specific.SpecificDatumWriter;

/**
 * Serializes/deserializes Avro {@link SpecificRecord} payloads and the {@link Envelope}
 * that wraps them, to and from plain {@code byte[]}.
 *
 * <p>Deliberately does NOT use the Confluent schema-registry serializer: the envelope's
 * {@code payload} field is itself {@code bytes} (ADR-0010's documented trade-off), so
 * plain Avro binary encoding is sufficient and keeps the build free of the Confluent
 * Maven repository dependency.
 */
@Component
public class EnvelopeCodec
{

    /**
     * Encode a single Avro {@link SpecificRecord} to its binary form.
     */
    public byte[] encodePayload(SpecificRecordBase payload)
    {
        try
        {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            BinaryEncoder encoder = EncoderFactory.get().binaryEncoder(out, null);
            SpecificDatumWriter<SpecificRecordBase> writer = new SpecificDatumWriter<>(payload.getSchema());
            writer.write(payload, encoder);
            encoder.flush();
            return out.toByteArray();
        }
        catch (IOException e)
        {
            throw new UncheckedIOException("Failed to encode Avro payload", e);
        }
    }

    /**
     * Decode a binary Avro payload of a known {@link SpecificRecordBase} type.
     */
    public <T extends SpecificRecordBase> T decodePayload(byte[] bytes, Class<T> type)
    {
        try
        {
            T instance = type.getDeclaredConstructor().newInstance();
            BinaryDecoder decoder = DecoderFactory.get().binaryDecoder(bytes, null);
            SpecificDatumReader<T> reader = new SpecificDatumReader<>(instance.getSchema());
            return reader.read(instance, decoder);
        }
        catch (ReflectiveOperationException e)
        {
            throw new IllegalStateException("Cannot instantiate Avro record " + type, e);
        }
        catch (IOException e)
        {
            throw new UncheckedIOException("Failed to decode Avro payload", e);
        }
    }

    /**
     * Build a new {@link Envelope} carrying the given Avro payload, and encode the
     * envelope itself to bytes ready to hand to the Kafka producer.
     */
    public byte[] encodeEnvelope(MessageKind kind,
                                 String type,
                                 UUID correlationId,
                                 UUID causationId,
                                 UUID aggregateId,
                                 SpecificRecordBase payload)
    {
        Envelope envelope = Envelope.newBuilder()
                .setMessageId(UUID.randomUUID())
                .setMessageKind(kind)
                .setCorrelationId(correlationId)
                .setCausationId(causationId)
                .setOccurredAt(Instant.now())
                .setType(type)
                .setSchemaVersion(1)
                .setTenantId("default")
                .setAggregateId(aggregateId.toString())
                // Propagated from the inbound request when the caller supplied one
                // (ADR-0013); null is schema-legal and means "no trace context arrived".
                .setTraceparent(TraceparentContext.current())
                .setPayload(ByteBuffer.wrap(encodePayload(payload)))
                .build();
        return encodeEnvelopeRecord(envelope);
    }

    private byte[] encodeEnvelopeRecord(Envelope envelope)
    {
        try
        {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            BinaryEncoder encoder = EncoderFactory.get().binaryEncoder(out, null);
            SpecificDatumWriter<Envelope> writer = new SpecificDatumWriter<>(Envelope.getClassSchema());
            writer.write(envelope, encoder);
            encoder.flush();
            return out.toByteArray();
        }
        catch (IOException e)
        {
            throw new UncheckedIOException("Failed to encode Envelope", e);
        }
    }

    /**
     * Decode a raw Kafka record value back into an {@link Envelope}.
     */
    public Envelope decodeEnvelope(byte[] bytes)
    {
        try
        {
            BinaryDecoder decoder = DecoderFactory.get().binaryDecoder(new ByteArrayInputStream(bytes), null);
            SpecificDatumReader<Envelope> reader = new SpecificDatumReader<>(Envelope.getClassSchema());
            return reader.read(null, decoder);
        }
        catch (IOException e)
        {
            throw new UncheckedIOException("Failed to decode Envelope", e);
        }
    }
}
