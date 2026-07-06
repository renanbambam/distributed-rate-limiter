package dev.renanbambam.ratelimiter.domain.algorithm;

import dev.renanbambam.ratelimiter.adapter.redis.RedisRateLimitStore;
import dev.renanbambam.ratelimiter.domain.model.RateLimitConfig;
import dev.renanbambam.ratelimiter.domain.model.RateLimitKey;
import dev.renanbambam.ratelimiter.domain.model.RateLimitResult;
import dev.renanbambam.ratelimiter.domain.port.RateLimiter;

import java.time.Instant;

// cheapest of the four, but a client can burst up to 2x the limit across
// a window boundary: limit requests right before it resets, limit again
// right after
public class FixedWindowRateLimiter implements RateLimiter {

    private final RedisRateLimitStore store;

    public FixedWindowRateLimiter(RedisRateLimitStore store) {
        this.store = store;
    }

    @Override
    public RateLimitResult isAllowed(RateLimitKey key, RateLimitConfig config) {
        long windowStart = (Instant.now().getEpochSecond() / config.windowSeconds()) * config.windowSeconds();
        String redisKey = key.toRedisKey(algorithmName()) + ":" + windowStart;

        long count = store.incrementAndExpire(redisKey, config.windowSeconds());
        long resetAt = windowStart + config.windowSeconds();

        if (count > config.limit()) {
            return RateLimitResult.blocked(resetAt, algorithmName());
        }
        return RateLimitResult.allowed((int) (config.limit() - count), resetAt, algorithmName());
    }

    @Override
    public String algorithmName() {
        return "fixed-window";
    }
}
