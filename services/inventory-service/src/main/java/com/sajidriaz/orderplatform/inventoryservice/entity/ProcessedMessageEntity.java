package com.sajidriaz.orderplatform.inventoryservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Consumer-side idempotency dedup (ADR-0005 layer 2). Written in the SAME DB transaction as
 * the stock change caused by consuming a command; a redelivered message whose
 * {@code (messageId, consumerGroup)} is already present is acknowledged and skipped without
 * reapplying the effect (scenario S-10).
 */
@Entity
@Table(name = "processed_message", schema = "inventory")
public class ProcessedMessageEntity {

    @EmbeddedId
    private Key id;

    @CreationTimestamp
    @Column(name = "processed_at", nullable = false, updatable = false)
    private Instant processedAt;

    protected ProcessedMessageEntity() {
        // JPA
    }

    public ProcessedMessageEntity(UUID messageId, String consumerGroup) {
        this.id = new Key(messageId, consumerGroup);
    }

    public Key getId() {
        return id;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }

    @Embeddable
    public static class Key implements Serializable {

        @Column(name = "message_id", nullable = false)
        private UUID messageId;

        @Column(name = "consumer_group", nullable = false, length = 160)
        private String consumerGroup;

        protected Key() {
            // JPA
        }

        public Key(UUID messageId, String consumerGroup) {
            this.messageId = messageId;
            this.consumerGroup = consumerGroup;
        }

        public UUID getMessageId() {
            return messageId;
        }

        public String getConsumerGroup() {
            return consumerGroup;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Key key)) {
                return false;
            }
            return Objects.equals(messageId, key.messageId)
                    && Objects.equals(consumerGroup, key.consumerGroup);
        }

        @Override
        public int hashCode() {
            return Objects.hash(messageId, consumerGroup);
        }
    }
}
