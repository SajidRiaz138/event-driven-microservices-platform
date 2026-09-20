package com.sajidriaz.orderplatform.inventoryservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * Stock for one SKU: the resource that must never be oversold (ADR-0015, scenario S-6).
 *
 * <p>Three quantities, deliberately distinct:
 * <ul>
 *   <li>{@code onHand} — physically held</li>
 *   <li>{@code reserved} — held for in-flight orders, not yet sold</li>
 *   <li>{@code available} — derived as {@code onHand - reserved}, never stored (a stored copy
 *       is a second source of truth that can disagree with itself)</li>
 * </ul>
 *
 * <p><strong>The reservation decision is not made here.</strong> Read-modify-write through this
 * entity — load, check {@code available}, set {@code reserved} — is exactly the race that
 * oversells: two transactions can both read the same last unit and both believe they may take
 * it. Reservation goes through {@code StockItemRepository.tryReserve}, a single atomic
 * conditional UPDATE. The methods here express the same invariant for unit-testing and for
 * paths that are genuinely single-writer (commit/release of an already-held reservation, where
 * the quantity being given back is known to exist), and {@link Version} guards those against
 * a concurrent modification.
 */
@Entity
@Table(name = "stock_item", schema = "inventory")
public class StockItemEntity {

    @Id
    @Column(name = "sku", nullable = false, length = 64)
    private String sku;

    @Column(name = "on_hand", nullable = false)
    private int onHand;

    @Column(name = "reserved", nullable = false)
    private int reserved;

    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected StockItemEntity() {
        // JPA
    }

    public StockItemEntity(String sku, int onHand, int reserved) {
        if (onHand < 0 || reserved < 0) {
            throw new IllegalArgumentException("quantities must be >= 0");
        }
        if (reserved > onHand) {
            throw new IllegalArgumentException("reserved must not exceed onHand");
        }
        this.sku = sku;
        this.onHand = onHand;
        this.reserved = reserved;
    }

    public String getSku() {
        return sku;
    }

    public int getOnHand() {
        return onHand;
    }

    public int getReserved() {
        return reserved;
    }

    public long getLockVersion() {
        return lockVersion;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** {@code onHand - reserved}: what a new reservation may draw on. */
    public int available() {
        return onHand - reserved;
    }

    public boolean canReserve(int quantity) {
        requirePositive(quantity);
        return available() >= quantity;
    }

    /**
     * The reservation invariant in domain form: increases {@code reserved} only when enough is
     * available. Returns false rather than throwing, because insufficient stock is a normal
     * business outcome (it becomes {@code StockReservationFailed}), never an error.
     */
    public boolean reserve(int quantity) {
        if (!canReserve(quantity)) {
            return false;
        }
        this.reserved += quantity;
        return true;
    }

    /** Give back a held quantity (compensation or TTL expiry). */
    public void releaseReserved(int quantity) {
        requirePositive(quantity);
        if (quantity > reserved) {
            throw new IllegalArgumentException(
                    "cannot release " + quantity + " for " + sku + "; only " + reserved + " is reserved");
        }
        this.reserved -= quantity;
    }

    /**
     * The sale completes: the held quantity leaves both {@code reserved} and {@code onHand}.
     * {@code available} is unchanged by this, which is the point — the stock was already
     * unavailable while reserved, and is now gone.
     */
    public void commitReserved(int quantity) {
        requirePositive(quantity);
        if (quantity > reserved) {
            throw new IllegalArgumentException(
                    "cannot commit " + quantity + " for " + sku + "; only " + reserved + " is reserved");
        }
        this.reserved -= quantity;
        this.onHand -= quantity;
    }

    private static void requirePositive(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be > 0: " + quantity);
        }
    }
}
