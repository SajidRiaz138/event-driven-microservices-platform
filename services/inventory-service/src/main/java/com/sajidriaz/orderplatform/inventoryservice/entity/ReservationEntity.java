package com.sajidriaz.orderplatform.inventoryservice.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A stock reservation with a TTL (ADR-0015): stock held for one order until the saga either
 * confirms it (committed), compensates it (released), or abandons it (expired).
 *
 * <p>The id is supplied by the caller — it is the {@code reservationId} from the
 * {@code ReserveStock} command, so a redelivered command for the same reservation is a no-op
 * rather than a second hold.
 */
@Entity
@Table(name = "reservation", schema = "inventory")
public class ReservationEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ReservationStatus status;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /**
     * Correlation id of the command that created this reservation, so an event emitted later
     * with no inbound message to copy it from — a TTL expiry — still joins the order's flow.
     */
    @Column(name = "correlation_id", nullable = false)
    private UUID correlationId;

    /**
     * Set at construction rather than by {@code @CreationTimestamp}. A generated-on-persist
     * timestamp is null until the row is written, and this value is read back when re-affirming
     * an existing reservation to a retrying orchestrator — a null there fails Avro encoding of the
     * reply. Owning the value explicitly removes that whole class of surprise.
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    @OneToMany(mappedBy = "reservation", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.EAGER)
    private List<ReservationLineEntity> lines = new ArrayList<>();

    protected ReservationEntity() {
        // JPA
    }

    public ReservationEntity(UUID id, UUID orderId, Instant expiresAt, UUID correlationId) {
        this.id = id;
        this.orderId = orderId;
        this.expiresAt = expiresAt;
        this.correlationId = correlationId;
        this.status = ReservationStatus.ACTIVE;
        this.createdAt = Instant.now();
    }

    public void addLine(String sku, int quantity) {
        lines.add(new ReservationLineEntity(this, sku, quantity));
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public ReservationStatus getStatus() {
        return status;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public UUID getCorrelationId() {
        return correlationId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public long getLockVersion() {
        return lockVersion;
    }

    public List<ReservationLineEntity> getLines() {
        return List.copyOf(lines);
    }

    public boolean isExpired(Instant now) {
        return status.isActive() && expiresAt.isBefore(now);
    }

    /** Resolve the reservation. Only an ACTIVE reservation may be resolved. */
    public void resolve(ReservationStatus resolution, Instant at) {
        if (resolution.isActive()) {
            throw new IllegalArgumentException("ACTIVE is not a resolution");
        }
        if (!status.isActive()) {
            throw new IllegalStateException(
                    "reservation " + id + " is already " + status + "; cannot become " + resolution);
        }
        this.status = resolution;
        this.resolvedAt = at;
    }
}
