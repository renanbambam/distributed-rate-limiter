package dev.renanbambam.ratelimiter.domain.algorithm;

import dev.renanbambam.ratelimiter.adapter.redis.RedisRateLimitStore;
import dev.renanbambam.ratelimiter.domain.model.RateLimitConfig;
import dev.renanbambam.ratelimiter.domain.model.RateLimitKey;
import dev.renanbambam.ratelimiter.domain.model.RateLimitResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FixedWindowRateLimiterTest {

    private final RedisRateLimitStore store = mock(RedisRateLimitStore.class);
    private final FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(store);
    private final RateLimitKey key = new RateLimitKey("client-1", "/api/demo");
    private final RateLimitConfig config = new RateLimitConfig(10, 60);

    @Test
    void allowsRequestWhenUnderLimit() {
        when(store.incrementAndExpire(any(), anyInt())).thenReturn(3L);

        RateLimitResult result = limiter.isAllowed(key, config);

        assertThat(result.allowed()).isTrue();
        assertThat(result.remaining()).isEqualTo(7);
        assertThat(result.algorithm()).isEqualTo("fixed-window");
    }

    @Test
    void blocksRequestWhenOverLimit() {
        when(store.incrementAndExpire(any(), anyInt())).thenReturn(11L);

        RateLimitResult result = limiter.isAllowed(key, config);

        assertThat(result.allowed()).isFalse();
        assertThat(result.remaining()).isZero();
    }

    @Test
    void allowsExactlyAtLimit() {
        when(store.incrementAndExpire(any(), anyInt())).thenReturn(10L);

        RateLimitResult result = limiter.isAllowed(key, config);

        assertThat(result.allowed()).isTrue();
        assertThat(result.remaining()).isZero();
    }
}
