package com.sajidriaz.orderplatform.orderservice.entity;

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
 * Edge (HTTP) idempotency record (ADR-0005 layer 1). Scope is
 * {@code customer (JWT sub) + method + path + key}; written in the SAME transaction
 * as the order it produced, so they cannot diverge (REST-API-GUIDE §1 "Idempotency").
 */
@Entity
@Table(name = "idempotency_key")
public class IdempotencyKeyEntity {

    @EmbeddedId
    private Key id;

    @Column(name = "request_hash", nullable = false, length = 128)
    private String requestHash;

    @Column(name = "response_status", nullable = false)
    private int responseStatus;

    @Column(name = "response_body", nullable = false)
    private String responseBody;

    @Column(name = "order_id")
    private UUID orderId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected IdempotencyKeyEntity() {
        // JPA
    }

    public IdempotencyKeyEntity(String key, String customerId, String method, String path,
                                 String requestHash, int responseStatus, String responseBody, UUID orderId) {
        this.id = new Key(key, customerId, method, path);
        this.requestHash = requestHash;
        this.responseStatus = responseStatus;
        this.responseBody = responseBody;
        this.orderId = orderId;
    }

    public Key getId() {
        return id;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public int getResponseStatus() {
        return responseStatus;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Embeddable
    public static class Key implements Serializable {

        @Column(name = "key", nullable = false, length = 128)
        private String key;

        @Column(name = "customer_id", nullable = false)
        private String customerId;

        @Column(name = "method", nullable = false, length = 10)
        private String method;

        @Column(name = "path", nullable = false)
        private String path;

        protected Key() {
            // JPA
        }

        public Key(String key, String customerId, String method, String path) {
            this.key = key;
            this.customerId = customerId;
            this.method = method;
            this.path = path;
        }

        public String getKey() {
            return key;
        }

        public String getCustomerId() {
            return customerId;
        }

        public String getMethod() {
            return method;
        }

        public String getPath() {
            return path;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Key that)) {
                return false;
            }
            return Objects.equals(key, that.key)
                    && Objects.equals(customerId, that.customerId)
                    && Objects.equals(method, that.method)
                    && Objects.equals(path, that.path);
        }

        @Override
        public int hashCode() {
            return Objects.hash(key, customerId, method, path);
        }
    }
}
