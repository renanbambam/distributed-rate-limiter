package dev.renanbambam.ratelimiter.domain.port;

import dev.renanbambam.ratelimiter.domain.model.RateLimitConfig;
import dev.renanbambam.ratelimiter.domain.model.RateLimitKey;
import dev.renanbambam.ratelimiter.domain.model.RateLimitResult;

// every algorithm implements this; swapping the bean in RateLimiterConfig
// is the only thing that changes between them
public interface RateLimiter {

    RateLimitResult isAllowed(RateLimitKey key, RateLimitConfig config);

    String algorithmName();
}
