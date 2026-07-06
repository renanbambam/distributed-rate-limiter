package dev.renanbambam.ratelimiter.config;

import dev.renanbambam.ratelimiter.adapter.redis.RedisRateLimitStore;
import dev.renanbambam.ratelimiter.domain.algorithm.FixedWindowRateLimiter;
import dev.renanbambam.ratelimiter.domain.algorithm.SlidingWindowCounterRateLimiter;
import dev.renanbambam.ratelimiter.domain.algorithm.SlidingWindowLogRateLimiter;
import dev.renanbambam.ratelimiter.domain.algorithm.TokenBucketRateLimiter;
import dev.renanbambam.ratelimiter.domain.port.RateLimiter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RateLimiterConfig {

    @Value("${rate-limiter.algorithm:token-bucket}")
    private String algorithm;

    @Bean
    public RateLimiter rateLimiter(RedisRateLimitStore store) {
        return switch (algorithm) {
            case "fixed-window" -> new FixedWindowRateLimiter(store);
            case "sliding-window-log" -> new SlidingWindowLogRateLimiter(store);
            case "sliding-window-counter" -> new SlidingWindowCounterRateLimiter(store);
            case "token-bucket" -> new TokenBucketRateLimiter(store);
            default -> throw new IllegalArgumentException("unknown rate-limiter.algorithm: " + algorithm);
        };
    }
}
