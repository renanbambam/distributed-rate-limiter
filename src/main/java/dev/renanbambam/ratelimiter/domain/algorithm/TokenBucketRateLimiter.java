package dev.renanbambam.ratelimiter.domain.algorithm;

import dev.renanbambam.ratelimiter.adapter.redis.RedisRateLimitStore;
import dev.renanbambam.ratelimiter.domain.model.RateLimitConfig;
import dev.renanbambam.ratelimiter.domain.model.RateLimitKey;
import dev.renanbambam.ratelimiter.domain.model.RateLimitResult;
import dev.renanbambam.ratelimiter.domain.model.TokenBucketState;
import dev.renanbambam.ratelimiter.domain.port.RateLimiter;

import java.time.Instant;

// bucket holds up to "limit" tokens, refills one per second. a client that's
// been idle can burst its whole capacity at once, then it's throttled to the
// refill rate - smoothest option here for legitimate bursty traffic
public class TokenBucketRateLimiter implements RateLimiter {

    private final RedisRateLimitStore store;

    public TokenBucketRateLimiter(RedisRateLimitStore store) {
        this.store = store;
    }

    @Override
    public RateLimitResult isAllowed(RateLimitKey key, RateLimitConfig config) {
        String redisKey = key.toRedisKey(algorithmName());
        long now = Instant.now().getEpochSecond();

        TokenBucketState state = store.consumeToken(redisKey, config.limit(), now);
        long resetAt = now + 1;

        if (!state.hasToken()) {
            return RateLimitResult.blocked(resetAt, algorithmName());
        }
        return RateLimitResult.allowed(state.remaining(), resetAt, algorithmName());
    }

    @Override
    public String algorithmName() {
        return "token-bucket";
    }
}
