package com.sajidriaz.orderplatform.inventoryservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.util.UUID;

/** One SKU + quantity held by a {@link ReservationEntity}. */
@Entity
@Table(name = "reservation_line", schema = "inventory")
public class ReservationLineEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "reservation_id", nullable = false)
    private ReservationEntity reservation;

    @Column(name = "sku", nullable = false, length = 64)
    private String sku;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    protected ReservationLineEntity() {
        // JPA
    }

    ReservationLineEntity(ReservationEntity reservation, String sku, int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be > 0: " + quantity);
        }
        this.reservation = reservation;
        this.sku = sku;
        this.quantity = quantity;
    }

    public UUID getId() {
        return id;
    }

    public ReservationEntity getReservation() {
        return reservation;
    }

    public String getSku() {
        return sku;
    }

    public int getQuantity() {
        return quantity;
    }
}
