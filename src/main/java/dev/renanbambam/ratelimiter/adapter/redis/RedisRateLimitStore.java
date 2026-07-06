package dev.renanbambam.ratelimiter.adapter.redis;

import dev.renanbambam.ratelimiter.domain.model.RateLimitDecision;
import dev.renanbambam.ratelimiter.domain.model.TokenBucketState;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

// only place in the codebase that touches RedisTemplate directly
@Component
@SuppressWarnings("unchecked")
public class RedisRateLimitStore {

    private static final RedisScript<Long> INCREMENT_AND_EXPIRE_SCRIPT =
            new DefaultRedisScript<>(LuaScripts.INCREMENT_AND_EXPIRE, Long.class);

    private static final RedisScript<List> CONSUME_TOKEN_SCRIPT =
            new DefaultRedisScript<>(LuaScripts.CONSUME_TOKEN, List.class);

    private static final RedisScript<List> SLIDING_WINDOW_LOG_SCRIPT =
            new DefaultRedisScript<>(LuaScripts.SLIDING_WINDOW_LOG, List.class);

    private static final RedisScript<List> SLIDING_WINDOW_COUNTER_SCRIPT =
            new DefaultRedisScript<>(LuaScripts.SLIDING_WINDOW_COUNTER, List.class);

    private final StringRedisTemplate redisTemplate;

    public RedisRateLimitStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /** Fixed Window: atomic INCR + first-write EXPIRE. */
    public long incrementAndExpire(String key, int ttlSeconds) {
        Long count = redisTemplate.execute(
                INCREMENT_AND_EXPIRE_SCRIPT,
                List.of(key),
                String.valueOf(ttlSeconds)
        );
        return count == null ? 0L : count;
    }

    /** Token Bucket: refill-then-consume, both in the same script invocation. */
    public TokenBucketState consumeToken(String key, int capacity, long nowEpochSecond) {
        List<Long> result = redisTemplate.execute(
                CONSUME_TOKEN_SCRIPT,
                List.of(key),
                String.valueOf(capacity),
                String.valueOf(nowEpochSecond)
        );
        boolean hasToken = result.get(0) == 1L;
        int remaining = result.get(1).intValue();
        return new TokenBucketState(hasToken, remaining);
    }

    /** Sliding Window Log: trim expired entries, then admit if under the limit. */
    public RateLimitDecision checkSlidingWindowLog(String key, long nowEpochSecond, int windowSeconds, int limit) {
        List<Long> result = redisTemplate.execute(
                SLIDING_WINDOW_LOG_SCRIPT,
                List.of(key),
                String.valueOf(nowEpochSecond),
                String.valueOf(windowSeconds),
                String.valueOf(limit)
        );
        boolean allowed = result.get(0) == 1L;
        int remaining = result.get(1).intValue();
        return new RateLimitDecision(allowed, remaining);
    }

    /** Sliding Window Counter: weighted estimate across the current and previous fixed windows. */
    public RateLimitDecision checkSlidingWindowCounter(
            String currentKey, String previousKey, int limit, int windowSeconds, long elapsedInWindowSeconds
    ) {
        List<Long> result = redisTemplate.execute(
                SLIDING_WINDOW_COUNTER_SCRIPT,
                List.of(currentKey, previousKey),
                String.valueOf(limit),
                String.valueOf(windowSeconds),
                String.valueOf(elapsedInWindowSeconds)
        );
        boolean allowed = result.get(0) == 1L;
        int remaining = result.get(1).intValue();
        return new RateLimitDecision(allowed, remaining);
    }
}
