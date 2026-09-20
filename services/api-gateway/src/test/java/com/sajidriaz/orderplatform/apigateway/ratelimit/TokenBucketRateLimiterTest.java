package com.sajidriaz.orderplatform.apigateway.ratelimit;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Time is driven by a controllable clock rather than by sleeping, so the refill behaviour is
 * asserted exactly instead of approximately.
 */
class TokenBucketRateLimiterTest
{

    private final AtomicLong nanos = new AtomicLong();

    @Test
    void allowsUpToCapacityThenDenies()
    {
        TokenBucketRateLimiter limiter = limiter(3, 1.0d);

        assertThat(limiter.tryConsume("client").allowed()).isTrue();
        assertThat(limiter.tryConsume("client").allowed()).isTrue();
        assertThat(limiter.tryConsume("client").allowed()).isTrue();

        TokenBucketRateLimiter.Decision denied = limiter.tryConsume("client");
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.remaining()).isZero();
        assertThat(denied.limit()).isEqualTo(3);
    }

    @Test
    void reportsRemainingTokens()
    {
        TokenBucketRateLimiter limiter = limiter(3, 1.0d);

        assertThat(limiter.tryConsume("client").remaining()).isEqualTo(2);
        assertThat(limiter.tryConsume("client").remaining()).isEqualTo(1);
        assertThat(limiter.tryConsume("client").remaining()).isZero();
    }

    @Test
    void refillsOverTime()
    {
        TokenBucketRateLimiter limiter = limiter(2, 1.0d);
        limiter.tryConsume("client");
        limiter.tryConsume("client");
        assertThat(limiter.tryConsume("client").allowed()).isFalse();

        advanceSeconds(1);

        assertThat(limiter.tryConsume("client").allowed()).isTrue();
        assertThat(limiter.tryConsume("client").allowed()).isFalse();
    }

    @Test
    void neverRefillsBeyondCapacity()
    {
        TokenBucketRateLimiter limiter = limiter(2, 1.0d);
        limiter.tryConsume("client");

        // Idle far longer than it takes to refill: the burst must stay bounded by capacity,
        // otherwise a client that waits accumulates an unbounded allowance.
        advanceSeconds(3_600);

        assertThat(limiter.tryConsume("client").allowed()).isTrue();
        assertThat(limiter.tryConsume("client").allowed()).isTrue();
        assertThat(limiter.tryConsume("client").allowed()).isFalse();
    }

    @Test
    void retryAfterIsAtLeastOneSecondAndReflectsTheRefillRate()
    {
        TokenBucketRateLimiter limiter = limiter(1, 0.25d);
        limiter.tryConsume("client");

        TokenBucketRateLimiter.Decision denied = limiter.tryConsume("client");

        // A quarter token per second means four seconds to the next one. Retry-After: 0 would
        // invite an immediate retry that would be refused again.
        assertThat(denied.retryAfterSeconds()).isEqualTo(4L);
    }

    @Test
    void bucketsArePerClient()
    {
        TokenBucketRateLimiter limiter = limiter(1, 1.0d);

        assertThat(limiter.tryConsume("client-a").allowed()).isTrue();
        assertThat(limiter.tryConsume("client-a").allowed()).isFalse();
        // One client exhausting its budget must not spend another's.
        assertThat(limiter.tryConsume("client-b").allowed()).isTrue();
    }

    @Test
    void rejectsNonsensicalConfiguration()
    {
        assertThatThrownBy(() -> new TokenBucketRateLimiter(0, 1.0d))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TokenBucketRateLimiter(1, 0.0d))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private TokenBucketRateLimiter limiter(int capacity, double refillPerSecond)
    {
        return new TokenBucketRateLimiter(capacity, refillPerSecond, nanos::get);
    }

    private void advanceSeconds(long seconds)
    {
        nanos.addAndGet(seconds * 1_000_000_000L);
    }
}
