package dev.renanbambam.ratelimiter.domain.model;

// same shape reused by every algorithm, though token-bucket treats "limit" as
// bucket capacity and ignores windowSeconds (it always refills 1/second)
public record RateLimitConfig(int limit, int windowSeconds) {

    public RateLimitConfig {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        if (windowSeconds <= 0) {
            throw new IllegalArgumentException("windowSeconds must be positive");
        }
    }
}
