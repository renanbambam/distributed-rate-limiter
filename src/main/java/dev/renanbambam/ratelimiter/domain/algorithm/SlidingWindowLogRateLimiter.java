package dev.renanbambam.ratelimiter.domain.algorithm;

import dev.renanbambam.ratelimiter.adapter.redis.RedisRateLimitStore;
import dev.renanbambam.ratelimiter.domain.model.RateLimitConfig;
import dev.renanbambam.ratelimiter.domain.model.RateLimitDecision;
import dev.renanbambam.ratelimiter.domain.model.RateLimitKey;
import dev.renanbambam.ratelimiter.domain.model.RateLimitResult;
import dev.renanbambam.ratelimiter.domain.port.RateLimiter;

import java.time.Instant;

// exact count, no boundary burst, but memory grows with request volume
// per client instead of staying constant
public class SlidingWindowLogRateLimiter implements RateLimiter {

    private final RedisRateLimitStore store;

    public SlidingWindowLogRateLimiter(RedisRateLimitStore store) {
        this.store = store;
    }

    @Override
    public RateLimitResult isAllowed(RateLimitKey key, RateLimitConfig config) {
        String redisKey = key.toRedisKey(algorithmName());
        long now = Instant.now().getEpochSecond();

        RateLimitDecision decision = store.checkSlidingWindowLog(redisKey, now, config.windowSeconds(), config.limit());
        long resetAt = now + config.windowSeconds();

        if (!decision.allowed()) {
            return RateLimitResult.blocked(resetAt, algorithmName());
        }
        return RateLimitResult.allowed(decision.remaining(), resetAt, algorithmName());
    }

    @Override
    public String algorithmName() {
        return "sliding-window-log";
    }
}
