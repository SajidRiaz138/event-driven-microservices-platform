package com.sajidriaz.orderplatform.inventoryservice.cache;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process {@link AvailabilityCache}: the default when Redis is not enabled, and what unit
 * tests and integration tests run against so neither needs a Redis container.
 *
 * <p>Deliberately bounded. An unbounded map keyed by SKU is a slow memory leak in a catalog of
 * unknown size, and "it is only a cache" is how that ships. On reaching the cap it clears
 * rather than evicting cleverly: this is a fallback for a display read, so a simple, obviously
 * correct policy beats an LRU that has to be maintained.
 *
 * <p>Not a distributed cache — with several replicas each holds its own copy, so an invalidation
 * on one replica does not reach the others. That is tolerable only because these values are
 * display-only and short-lived; it is also exactly why Redis is the real deployment choice.
 */
public class InMemoryAvailabilityCache implements AvailabilityCache {

    private static final int MAX_ENTRIES = 10_000;

    private final Map<String, Integer> entries = new ConcurrentHashMap<>();

    @Override
    public Optional<Integer> get(String sku) {
        return Optional.ofNullable(entries.get(sku));
    }

    @Override
    public void put(String sku, int available) {
        if (entries.size() >= MAX_ENTRIES && !entries.containsKey(sku)) {
            entries.clear();
        }
        entries.put(sku, available);
    }

    @Override
    public void invalidate(String sku) {
        entries.remove(sku);
    }

    @Override
    public String kind() {
        return "in-memory";
    }
}
