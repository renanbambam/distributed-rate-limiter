package dev.renanbambam.ratelimiter.adapter.redis;

// each of these has to run as one atomic round trip, otherwise two concurrent
// requests can both read "9 out of 10" and both get let through. Redis runs
// a script single-threaded, so this replaces what would otherwise need a
// distributed lock
public final class LuaScripts {

    private LuaScripts() {
    }

    /** Fixed Window: increment the counter for the current window and set its TTL on first write. */
    public static final String INCREMENT_AND_EXPIRE = """
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
                redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return count
            """;

    /** Token Bucket: refill proportionally to elapsed time, then try to spend one token. */
    public static final String CONSUME_TOKEN = """
            local key = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local now = tonumber(ARGV[2])

            local data = redis.call('HMGET', key, 'tokens', 'last_refill')
            local tokens = tonumber(data[1]) or capacity
            local last_refill = tonumber(data[2]) or now

            local elapsed = math.max(0, now - last_refill)
            local refilled = math.min(capacity, tokens + elapsed)

            if refilled < 1 then
                redis.call('HMSET', key, 'tokens', refilled, 'last_refill', now)
                redis.call('EXPIRE', key, 60)
                return {0, math.floor(refilled)}
            end

            local remaining = math.floor(refilled - 1)
            redis.call('HMSET', key, 'tokens', remaining, 'last_refill', now)
            redis.call('EXPIRE', key, 60)
            return {1, remaining}
            """;

    /** Sliding Window Log: drop timestamps older than the window, then admit if there's room. */
    public static final String SLIDING_WINDOW_LOG = """
            local key = KEYS[1]
            local now = tonumber(ARGV[1])
            local window = tonumber(ARGV[2])
            local limit = tonumber(ARGV[3])

            redis.call('ZREMRANGEBYSCORE', key, '-inf', now - window)
            local count = redis.call('ZCARD', key)

            if count >= limit then
                return {0, 0}
            end

            redis.call('ZADD', key, now, now .. '-' .. math.random())
            redis.call('EXPIRE', key, window)
            return {1, limit - count - 1}
            """;

    /** Sliding Window Counter: weight the previous window's count by overlap, add the current one. */
    public static final String SLIDING_WINDOW_COUNTER = """
            local current_key = KEYS[1]
            local previous_key = KEYS[2]
            local limit = tonumber(ARGV[1])
            local window = tonumber(ARGV[2])
            local elapsed_in_window = tonumber(ARGV[3])

            local previous_count = tonumber(redis.call('GET', previous_key)) or 0
            local current_count = tonumber(redis.call('GET', current_key)) or 0

            local weight = (window - elapsed_in_window) / window
            local estimated = (previous_count * weight) + current_count

            if estimated >= limit then
                return {0, 0}
            end

            local new_current = redis.call('INCR', current_key)
            if new_current == 1 then
                redis.call('EXPIRE', current_key, window * 2)
            end

            local remaining = math.floor(limit - estimated - 1)
            if remaining < 0 then
                remaining = 0
            end
            return {1, remaining}
            """;
}
