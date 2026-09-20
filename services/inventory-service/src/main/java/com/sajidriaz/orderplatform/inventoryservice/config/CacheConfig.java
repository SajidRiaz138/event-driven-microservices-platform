package com.sajidriaz.orderplatform.inventoryservice.config;

import com.sajidriaz.orderplatform.inventoryservice.cache.AvailabilityCache;
import com.sajidriaz.orderplatform.inventoryservice.cache.InMemoryAvailabilityCache;
import com.sajidriaz.orderplatform.inventoryservice.cache.RedisAvailabilityCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/**
 * Chooses the availability cache implementation.
 *
 * <p>Redis is the intended deployment (ADR-0007) but is opt-in via
 * {@code inventory.cache.redis.enabled}, so the service — and every test — runs with no Redis
 * present. Both implementations are behind {@link AvailabilityCache}, and neither is consulted
 * on the reservation path: availability for a <em>decision</em> always comes from the atomic
 * conditional UPDATE in Postgres (ADR-0015).
 */
@Configuration
public class CacheConfig {

    private static final Logger log = LoggerFactory.getLogger(CacheConfig.class);

    @Bean
    @ConditionalOnProperty(name = "inventory.cache.redis.enabled", havingValue = "true")
    public AvailabilityCache redisAvailabilityCache(
            StringRedisTemplate redisTemplate,
            @Value("${inventory.cache.ttl-seconds:30}") long ttlSeconds) {
        log.info("Availability cache: Redis (display-only, ttl={}s)", ttlSeconds);
        return new RedisAvailabilityCache(redisTemplate, Duration.ofSeconds(ttlSeconds));
    }

    @Bean
    @ConditionalOnProperty(name = "inventory.cache.redis.enabled", havingValue = "false",
            matchIfMissing = true)
    public AvailabilityCache inMemoryAvailabilityCache() {
        log.info("Availability cache: in-process (display-only; set inventory.cache.redis.enabled=true "
                + "for the distributed cache)");
        return new InMemoryAvailabilityCache();
    }
}
