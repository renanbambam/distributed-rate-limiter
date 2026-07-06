package dev.renanbambam.ratelimiter.domain.algorithm;

import dev.renanbambam.ratelimiter.adapter.redis.RedisRateLimitStore;
import dev.renanbambam.ratelimiter.domain.model.RateLimitConfig;
import dev.renanbambam.ratelimiter.domain.model.RateLimitDecision;
import dev.renanbambam.ratelimiter.domain.model.RateLimitKey;
import dev.renanbambam.ratelimiter.domain.model.RateLimitResult;
import dev.renanbambam.ratelimiter.domain.port.RateLimiter;

import java.time.Instant;

// weights the previous window's count by how much it still overlaps the
// trailing window and adds it to the current one - fixed memory like Fixed
// Window, but it assumes requests are spread evenly within a window, which
// is off at the edges
public class SlidingWindowCounterRateLimiter implements RateLimiter {

    private final RedisRateLimitStore store;

    public SlidingWindowCounterRateLimiter(RedisRateLimitStore store) {
        this.store = store;
    }

    @Override
    public RateLimitResult isAllowed(RateLimitKey key, RateLimitConfig config) {
        int windowSeconds = config.windowSeconds();
        long now = Instant.now().getEpochSecond();
        long currentWindowStart = (now / windowSeconds) * windowSeconds;
        long previousWindowStart = currentWindowStart - windowSeconds;
        long elapsedInWindow = now - currentWindowStart;

        String baseKey = key.toRedisKey(algorithmName());
        String currentKey = baseKey + ":" + currentWindowStart;
        String previousKey = baseKey + ":" + previousWindowStart;

        RateLimitDecision decision = store.checkSlidingWindowCounter(
                currentKey, previousKey, config.limit(), windowSeconds, elapsedInWindow
        );
        long resetAt = currentWindowStart + windowSeconds;

        if (!decision.allowed()) {
            return RateLimitResult.blocked(resetAt, algorithmName());
        }
        return RateLimitResult.allowed(decision.remaining(), resetAt, algorithmName());
    }

    @Override
    public String algorithmName() {
        return "sliding-window-counter";
    }
}
