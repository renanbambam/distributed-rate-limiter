package dev.renanbambam.ratelimiter.domain.model;

public record RateLimitResult(
        boolean allowed,
        int remaining,
        long resetAtEpochSecond,
        String algorithm
) {

    public static RateLimitResult allowed(int remaining, long resetAt, String algorithm) {
        return new RateLimitResult(true, remaining, resetAt, algorithm);
    }

    public static RateLimitResult blocked(long resetAt, String algorithm) {
        return new RateLimitResult(false, 0, resetAt, algorithm);
    }
}
