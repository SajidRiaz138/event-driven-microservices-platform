package com.sajidriaz.orderplatform.inventoryservice.service;

import com.sajidriaz.orderplatform.inventoryservice.cache.AvailabilityCache;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Availability reads for <strong>display</strong>, cache-aside (ADR-0007): check the cache, fall
 * back to the database, populate the cache. Invalidation happens wherever stock changes, in
 * {@link InventoryService}.
 *
 * <p>This class is the only reader of the cache, and nothing on the reservation path calls it.
 * That separation is the design: a cached number is possibly stale, and a reservation decided
 * from a stale number is an oversell. Reservations go through the atomic conditional UPDATE
 * (ADR-0015); this is for showing a customer roughly how many are left.
 *
 * <p>Hit/miss counters feed the cache-hit-ratio metric ADR-0013 asks for.
 */
@Service
public class AvailabilityQueryService {

    private final InventoryService inventoryService;
    private final AvailabilityCache cache;
    private final Counter hits;
    private final Counter misses;

    public AvailabilityQueryService(InventoryService inventoryService, AvailabilityCache cache,
                                    MeterRegistry meterRegistry) {
        this.inventoryService = inventoryService;
        this.cache = cache;
        this.hits = Counter.builder("inventory.availability.cache")
                .tag("result", "hit").tag("cache", cache.kind())
                .description("Availability display reads served from the cache")
                .register(meterRegistry);
        this.misses = Counter.builder("inventory.availability.cache")
                .tag("result", "miss").tag("cache", cache.kind())
                .description("Availability display reads that fell through to the database")
                .register(meterRegistry);
    }

    /** Availability for display, or empty when the SKU is unknown. */
    public Optional<Integer> availableForDisplay(String sku) {
        Optional<Integer> cached = cache.get(sku);
        if (cached.isPresent()) {
            hits.increment();
            return cached;
        }
        misses.increment();
        Optional<Integer> fromDatabase = inventoryService.availableFromDatabase(sku);
        // An unknown SKU is deliberately not cached: caching absence would need its own
        // invalidation the moment the SKU is created.
        fromDatabase.ifPresent(available -> cache.put(sku, available));
        return fromDatabase;
    }
}
