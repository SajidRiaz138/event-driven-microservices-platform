package com.sajidriaz.orderplatform.inventoryservice.entity;

/**
 * Lifecycle of a stock reservation (ADR-0015).
 *
 * <p>{@code ACTIVE} is the only status that holds stock and the only one subject to the TTL.
 * The three resolved statuses are kept distinct rather than collapsed into one "closed" state
 * because they mean different things operationally: a {@code RELEASED} reservation was
 * compensated by the saga, an {@code EXPIRED} one was abandoned, and a {@code COMMITTED} one
 * was sold — and a released-vs-expired distinction is what tells you whether abandonment is a
 * growing problem.
 */
public enum ReservationStatus {

    /** Holding stock; expires at {@code expires_at} unless committed or released first. */
    ACTIVE,

    /** The order was confirmed: stock left both {@code reserved} and {@code on_hand}. */
    COMMITTED,

    /** Released by a {@code ReleaseStock} command (saga compensation). */
    RELEASED,

    /** Released by the TTL sweeper because no outcome ever arrived. */
    EXPIRED;

    public boolean isActive() {
        return this == ACTIVE;
    }

    /** True once the reservation no longer holds stock. */
    public boolean isResolved() {
        return this != ACTIVE;
    }
}
