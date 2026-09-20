package com.sajidriaz.orderplatform.inventoryservice.cache;

import java.util.Optional;

/**
 * Cache for availability <strong>display reads only</strong> (ADR-0007).
 *
 * <p>The invariant this abstraction exists to protect: <strong>a cached value must never back a
 * reservation decision.</strong> A reservation is always decided by the atomic conditional
 * UPDATE in Postgres (ADR-0015); a cache is by definition possibly stale, and deciding
 * "available" from a stale number is precisely how a system oversells. So nothing on the
 * reservation path consults this interface — it is read only by the availability query used for
 * display, and written/invalidated when stock changes.
 *
 * <p>It is an interface, rather than a direct {@code RedisTemplate} call, for two reasons: the
 * service must run and be fully testable with no Redis present, and a cache that cannot be
 * swapped for a no-op is a cache that quietly becomes load-bearing.
 */
public interface AvailabilityCache {

    /** Cached availability for a SKU, or empty on a miss. */
    Optional<Integer> get(String sku);

    /** Cache an availability value read from the database. */
    void put(String sku, int available);

    /** Drop a SKU's cached value because its stock changed. */
    void invalidate(String sku);

    /** Implementation name, for logging and the actuator info/metrics tags. */
    String kind();
}
