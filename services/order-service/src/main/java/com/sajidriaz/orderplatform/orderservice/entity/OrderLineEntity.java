package com.sajidriaz.orderplatform.orderservice.entity;

import com.sajidriaz.orderplatform.common.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * A persisted order line with the server-authoritative price snapshot (unit price at
 * order time). Clients never send prices (REST-API-GUIDE §1 "Pricing").
 */
@Entity
@Table(name = "order_lines")
public class OrderLineEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private OrderEntity order;

    @Column(name = "sku", nullable = false, length = 64)
    private String sku;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "unit_price_minor_units", nullable = false)
    private long unitPriceMinorUnits;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    protected OrderLineEntity() {
        // JPA
    }

    public OrderLineEntity(String sku, int quantity, Money unitPrice) {
        this.sku = sku;
        this.quantity = quantity;
        this.unitPriceMinorUnits = unitPrice.minorUnits();
        this.currency = unitPrice.currency();
    }

    public UUID getId() {
        return id;
    }

    public OrderEntity getOrder() {
        return order;
    }

    public void setOrder(OrderEntity order) {
        this.order = order;
    }

    public String getSku() {
        return sku;
    }

    public int getQuantity() {
        return quantity;
    }

    public long getUnitPriceMinorUnits() {
        return unitPriceMinorUnits;
    }

    public String getCurrency() {
        return currency;
    }

    public Money unitPrice() {
        return new Money(unitPriceMinorUnits, currency);
    }

    public long getLineTotalMinorUnits() {
        return unitPriceMinorUnits * quantity;
    }

    public Money lineTotal() {
        return new Money(getLineTotalMinorUnits(), currency);
    }
}
