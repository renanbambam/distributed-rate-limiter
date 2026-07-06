package dev.renanbambam.ratelimiter.domain.algorithm;

import dev.renanbambam.ratelimiter.adapter.redis.RedisRateLimitStore;
import dev.renanbambam.ratelimiter.domain.model.RateLimitConfig;
import dev.renanbambam.ratelimiter.domain.model.RateLimitDecision;
import dev.renanbambam.ratelimiter.domain.model.RateLimitKey;
import dev.renanbambam.ratelimiter.domain.model.RateLimitResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SlidingWindowLogRateLimiterTest {

    private final RedisRateLimitStore store = mock(RedisRateLimitStore.class);
    private final SlidingWindowLogRateLimiter limiter = new SlidingWindowLogRateLimiter(store);
    private final RateLimitKey key = new RateLimitKey("client-1", "/api/demo");
    private final RateLimitConfig config = new RateLimitConfig(10, 60);

    @Test
    void allowsRequestWhenUnderLimit() {
        when(store.checkSlidingWindowLog(any(), anyLong(), anyInt(), anyInt()))
                .thenReturn(new RateLimitDecision(true, 6));

        RateLimitResult result = limiter.isAllowed(key, config);

        assertThat(result.allowed()).isTrue();
        assertThat(result.remaining()).isEqualTo(6);
        assertThat(result.algorithm()).isEqualTo("sliding-window-log");
    }

    @Test
    void blocksRequestWhenLogIsFull() {
        when(store.checkSlidingWindowLog(any(), anyLong(), anyInt(), anyInt()))
                .thenReturn(new RateLimitDecision(false, 0));

        RateLimitResult result = limiter.isAllowed(key, config);

        assertThat(result.allowed()).isFalse();
        assertThat(result.remaining()).isZero();
    }
}
