package dev.renanbambam.ratelimiter.adapter.rest;

import dev.renanbambam.ratelimiter.domain.model.RateLimitResult;
import jakarta.servlet.http.HttpServletResponse;

public final class RateLimitHeaders {

    private static final String LIMIT = "X-RateLimit-Limit";
    private static final String REMAINING = "X-RateLimit-Remaining";
    private static final String RESET = "X-RateLimit-Reset";
    private static final String ALGORITHM = "X-RateLimit-Algorithm";

    private RateLimitHeaders() {
    }

    public static void write(HttpServletResponse response, RateLimitResult result, int limit) {
        response.setHeader(LIMIT, String.valueOf(limit));
        response.setHeader(REMAINING, String.valueOf(result.remaining()));
        response.setHeader(RESET, String.valueOf(result.resetAtEpochSecond()));
        response.setHeader(ALGORITHM, result.algorithm());
    }
}
