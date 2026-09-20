package com.sajidriaz.orderplatform.orderservice.entity;

import com.sajidriaz.orderplatform.common.saga.SagaStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The order aggregate. {@code status} holds the {@link SagaStatus} name (internal,
 * finer-grained states incl. {@code REQUIRES_RECONCILIATION}); the REST layer
 * projects this down to the public {@code PENDING|CONFIRMED|CANCELLED} view
 * (REST-API-GUIDE §3). Money is minor-units + currency — never a floating type.
 */
@Entity
@Table (name = "orders")
public class OrderEntity
{

    @Id
    @GeneratedValue (strategy = GenerationType.UUID)
    private UUID id;

    @Column (name = "customer_id", nullable = false)
    private String customerId;

    @Column (name = "currency", nullable = false, length = 3)
    private String currency;

    @Column (name = "total_minor_units", nullable = false)
    private long totalMinorUnits;

    @Enumerated (EnumType.STRING)
    @Column (name = "status", nullable = false, length = 40)
    private SagaStatus status = SagaStatus.PENDING;

    @Enumerated (EnumType.STRING)
    @Column (name = "cancellation_reason", length = 40)
    private CancellationReason cancellationReason;

    @Column (name = "payment_instrument_id", nullable = false, length = 128)
    private String paymentInstrumentId;

    @OneToMany (mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<OrderLineEntity> lines = new ArrayList<>();

    @CreationTimestamp
    @Column (name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column (name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected OrderEntity()
    {
        // JPA
    }

    public OrderEntity(String customerId, String currency, String paymentInstrumentId)
    {
        this.customerId = customerId;
        this.currency = currency;
        this.paymentInstrumentId = paymentInstrumentId;
        this.status = SagaStatus.PENDING;
    }

    public void addLine(OrderLineEntity line)
    {
        line.setOrder(this);
        this.lines.add(line);
        this.totalMinorUnits += line.getLineTotalMinorUnits();
    }

    public UUID getId()
    {
        return id;
    }

    public String getCustomerId()
    {
        return customerId;
    }

    public String getCurrency()
    {
        return currency;
    }

    public long getTotalMinorUnits()
    {
        return totalMinorUnits;
    }

    public SagaStatus getStatus()
    {
        return status;
    }

    public void setStatus(SagaStatus status)
    {
        this.status = status;
    }

    public CancellationReason getCancellationReason()
    {
        return cancellationReason;
    }

    public void setCancellationReason(CancellationReason cancellationReason)
    {
        this.cancellationReason = cancellationReason;
    }

    public String getPaymentInstrumentId()
    {
        return paymentInstrumentId;
    }

    public List<OrderLineEntity> getLines()
    {
        return lines;
    }

    public Instant getCreatedAt()
    {
        return createdAt;
    }

    public Instant getUpdatedAt()
    {
        return updatedAt;
    }

    public boolean isOwnedBy(String callerCustomerId)
    {
        return this.customerId != null && this.customerId.equals(callerCustomerId);
    }
}
