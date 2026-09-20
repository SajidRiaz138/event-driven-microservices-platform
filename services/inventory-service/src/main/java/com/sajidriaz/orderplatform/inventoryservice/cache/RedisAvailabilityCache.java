package com.sajidriaz.orderplatform.inventoryservice.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Optional;

/**
 * Redis-backed {@link AvailabilityCache}: the distributed cache for availability display reads
 * (ADR-0007), used when {@code inventory.cache.redis.enabled=true}.
 *
 * <p>Every entry carries a TTL. A cache that depends solely on explicit invalidation to stay
 * correct will eventually serve a stale value forever, because invalidation is the step that
 * gets missed — a crash between the DB commit and the Redis delete is enough. The TTL bounds how
 * wrong a display number can be even then.
 *
 * <p>Redis failures are swallowed and logged, never propagated. This cache serves a display
 * read; an unavailable Redis must degrade to reading Postgres, not fail the request. Failing
 * closed here would let a cache outage take down an endpoint that does not need it.
 */
public class RedisAvailabilityCache implements AvailabilityCache {

    private static final Logger log = LoggerFactory.getLogger(RedisAvailabilityCache.class);
    private static final String KEY_PREFIX = "inventory:availability:";

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public RedisAvailabilityCache(StringRedisTemplate redis, Duration ttl) {
        this.redis = redis;
        this.ttl = ttl;
    }

    @Override
    public Optional<Integer> get(String sku) {
        try {
            String value = redis.opsForValue().get(key(sku));
            return value == null ? Optional.empty() : Optional.of(Integer.valueOf(value));
        } catch (NumberFormatException e) {
            // A non-numeric value means something else wrote this key; drop it rather than
            // letting it break every subsequent read.
            log.warn("Discarding non-numeric cached availability for sku {}", sku);
            invalidate(sku);
            return Optional.empty();
        } catch (RuntimeException e) {
            log.warn("Availability cache read failed for sku {}; falling back to the database", sku, e);
            return Optional.empty();
        }
    }

    @Override
    public void put(String sku, int available) {
        try {
            redis.opsForValue().set(key(sku), Integer.toString(available), ttl);
        } catch (RuntimeException e) {
            log.warn("Availability cache write failed for sku {}; continuing uncached", sku, e);
        }
    }

    @Override
    public void invalidate(String sku) {
        try {
            redis.delete(key(sku));
        } catch (RuntimeException e) {
            // Worth a louder log than a failed read: a missed invalidation is what leaves a
            // stale value behind. The entry TTL is the reason this is survivable.
            log.warn("Availability cache invalidation failed for sku {}; the entry will go stale "
                    + "until its TTL expires", sku, e);
        }
    }

    @Override
    public String kind() {
        return "redis";
    }

    private String key(String sku) {
        return KEY_PREFIX + sku;
    }
}
