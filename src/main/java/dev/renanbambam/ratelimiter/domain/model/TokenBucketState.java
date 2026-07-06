package dev.renanbambam.ratelimiter.domain.model;

public record TokenBucketState(boolean hasToken, int remaining) {
}
