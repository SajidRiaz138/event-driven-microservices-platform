package com.sajidriaz.orderplatform.orderservice.entity;

import com.sajidriaz.orderplatform.common.outbox.OutboxStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * A transactional outbox row (ADR-0004). Written in the SAME DB transaction as the
 * business change it announces; a separate relay ({@code OutboxRelay}) drains
 * {@code PENDING} rows and publishes them to Kafka, marking them {@code SENT}.
 */
@Entity
@Table(name = "outbox")
public class OutboxRecordEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    /** Fully-qualified message type, e.g. {@code events.order.created} (ADR-0010). */
    @Column(name = "message_type", nullable = false, length = 160)
    private String messageType;

    @Column(name = "message_kind", nullable = false, length = 10)
    private String messageKind;

    /** Concrete Kafka topic, e.g. {@code events.order.created.v1}. */
    @Column(name = "topic", nullable = false, length = 160)
    private String topic;

    /** The fully Avro-serialized {@code Envelope}, ready to publish as-is. */
    @Column(name = "payload", nullable = false)
    private byte[] payload;

    @Column(name = "headers")
    private String headers;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private OutboxStatus status = OutboxStatus.PENDING;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    protected OutboxRecordEntity() {
        // JPA
    }

    public OutboxRecordEntity(UUID aggregateId, String messageType, String messageKind,
                               String topic, byte[] payload) {
        this.aggregateId = aggregateId;
        this.messageType = messageType;
        this.messageKind = messageKind;
        this.topic = topic;
        this.payload = payload;
        this.status = OutboxStatus.PENDING;
    }

    public UUID getId() {
        return id;
    }

    public UUID getAggregateId() {
        return aggregateId;
    }

    public String getMessageType() {
        return messageType;
    }

    public String getMessageKind() {
        return messageKind;
    }

    public String getTopic() {
        return topic;
    }

    public byte[] getPayload() {
        return payload;
    }

    public String getHeaders() {
        return headers;
    }

    public OutboxStatus getStatus() {
        return status;
    }

    public void markSent(Instant sentAt) {
        this.status = OutboxStatus.SENT;
        this.sentAt = sentAt;
    }

    public void markFailed() {
        this.status = OutboxStatus.FAILED;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getSentAt() {
        return sentAt;
    }
}
