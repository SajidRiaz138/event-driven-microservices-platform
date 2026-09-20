package com.sajidriaz.orderplatform.apigateway.ratelimit;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Per-client token bucket.
 *
 * <p>A bucket holds up to {@code capacity} tokens and refills at {@code refillPerSecond};
 * each request costs one token. That shape is chosen over a fixed window on purpose: a fixed
 * window lets a client spend its whole quota in the last instant of one window and again in the
 * first instant of the next, so the burst a limiter exists to contain arrives anyway. A bucket
 * bounds both the burst (capacity) and the sustained rate (refill) independently.
 *
 * <p>State is in memory, which is the honest scope of this implementation: it limits per gateway
 * instance, so N instances allow N times the budget. That is fine for a single-instance local
 * stack and for protecting a downstream from one misbehaving client, and it is not a
 * distributed quota — that needs shared state (Redis) and is a deliberate later step rather
 * than something to fake here.
 *
 * <p>The clock is injected so the tests can advance time instead of sleeping; a limiter tested
 * with {@code Thread.sleep} is a limiter tested flakily.
 */
public class TokenBucketRateLimiter
{

    private final int capacity;
    private final double refillPerSecond;
    private final LongSupplier nanoTime;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public TokenBucketRateLimiter(int capacity, double refillPerSecond)
    {
        this(capacity, refillPerSecond, System::nanoTime);
    }

    TokenBucketRateLimiter(int capacity, double refillPerSecond, LongSupplier nanoTime)
    {
        if (capacity <= 0 || refillPerSecond <= 0)
        {
            throw new IllegalArgumentException("capacity and refillPerSecond must be positive");
        }
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
        this.nanoTime = nanoTime;
    }

    /**
     * Charges one token to {@code clientKey}.
     *
     * @return whether the request is allowed, plus the numbers the response advertises
     */
    public Decision tryConsume(String clientKey)
    {
        Bucket bucket = buckets.computeIfAbsent(clientKey, key -> new Bucket(capacity, nanoTime.getAsLong()));
        return bucket.tryConsume();
    }

    /**
     * @param allowed           whether the caller may proceed
     * @param limit             bucket capacity, for {@code X-RateLimit-Limit}
     * @param remaining         whole tokens left, for {@code X-RateLimit-Remaining}
     * @param retryAfterSeconds seconds until the next token, for {@code Retry-After}; at least 1
     *                          when denied, because {@code Retry-After: 0} invites an immediate
     *                          retry that would be denied again
     */
    public record Decision(boolean allowed, int limit, int remaining, long retryAfterSeconds) {
    }

    private final class Bucket
    {

        private double tokens;
        private long lastRefillNanos;

        private Bucket(double tokens, long nowNanos)
        {
            this.tokens = tokens;
            this.lastRefillNanos = nowNanos;
        }

        private synchronized Decision tryConsume()
        {
            refill();
            if (tokens >= 1.0d)
            {
                tokens -= 1.0d;
                return new Decision(true, capacity, (int) Math.floor(tokens), 0L);
            }
            double secondsUntilNextToken = (1.0d - tokens) / refillPerSecond;
            return new Decision(false, capacity, 0, Math.max(1L, (long) Math.ceil(secondsUntilNextToken)));
        }

        private void refill()
        {
            long now = nanoTime.getAsLong();
            double elapsedSeconds = (now - lastRefillNanos) / 1_000_000_000.0d;
            if (elapsedSeconds > 0)
            {
                tokens = Math.min(capacity, tokens + elapsedSeconds * refillPerSecond);
                lastRefillNanos = now;
            }
        }
    }
}
