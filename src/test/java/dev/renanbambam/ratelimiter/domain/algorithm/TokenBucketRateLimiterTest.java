package dev.renanbambam.ratelimiter.domain.algorithm;

import dev.renanbambam.ratelimiter.adapter.redis.RedisRateLimitStore;
import dev.renanbambam.ratelimiter.domain.model.RateLimitConfig;
import dev.renanbambam.ratelimiter.domain.model.RateLimitKey;
import dev.renanbambam.ratelimiter.domain.model.RateLimitResult;
import dev.renanbambam.ratelimiter.domain.model.TokenBucketState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TokenBucketRateLimiterTest {

    private final RedisRateLimitStore store = mock(RedisRateLimitStore.class);
    private final TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(store);
    private final RateLimitKey key = new RateLimitKey("client-1", "/api/demo");
    private final RateLimitConfig config = new RateLimitConfig(10, 60);

    @Test
    void allowsRequestWhenTokenAvailable() {
        when(store.consumeToken(any(), anyInt(), anyLong())).thenReturn(new TokenBucketState(true, 4));

        RateLimitResult result = limiter.isAllowed(key, config);

        assertThat(result.allowed()).isTrue();
        assertThat(result.remaining()).isEqualTo(4);
        assertThat(result.algorithm()).isEqualTo("token-bucket");
    }

    @Test
    void blocksRequestWhenBucketEmpty() {
        when(store.consumeToken(any(), anyInt(), anyLong())).thenReturn(new TokenBucketState(false, 0));

        RateLimitResult result = limiter.isAllowed(key, config);

        assertThat(result.allowed()).isFalse();
        assertThat(result.remaining()).isZero();
    }
}
