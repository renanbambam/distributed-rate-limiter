package dev.renanbambam.ratelimiter.domain.model;

public record RateLimitDecision(boolean allowed, int remaining) {
}
