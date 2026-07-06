package dev.renanbambam.ratelimiter.domain.model;

public record RateLimitKey(String clientId, String endpoint) {

    public String toRedisKey(String algorithm) {
        return "rl:%s:%s:%s".formatted(algorithm, clientId, endpoint);
    }
}
