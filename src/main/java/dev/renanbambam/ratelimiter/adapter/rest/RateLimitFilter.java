package dev.renanbambam.ratelimiter.adapter.rest;

import dev.renanbambam.ratelimiter.domain.model.RateLimitConfig;
import dev.renanbambam.ratelimiter.domain.model.RateLimitKey;
import dev.renanbambam.ratelimiter.domain.model.RateLimitResult;
import dev.renanbambam.ratelimiter.domain.port.RateLimiter;
import dev.renanbambam.ratelimiter.metrics.RateLimiterMetrics;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(1)
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimiter rateLimiter;
    private final RateLimiterMetrics metrics;

    @Value("${rate-limiter.limit:10}")
    private int limit;

    @Value("${rate-limiter.window-seconds:60}")
    private int windowSeconds;

    public RateLimitFilter(RateLimiter rateLimiter, RateLimiterMetrics metrics) {
        this.rateLimiter = rateLimiter;
        this.metrics = metrics;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String clientId = resolveClientId(request);
        RateLimitKey key = new RateLimitKey(clientId, request.getRequestURI());
        RateLimitConfig config = new RateLimitConfig(limit, windowSeconds);

        RateLimitResult result = rateLimiter.isAllowed(key, config);
        RateLimitHeaders.write(response, result, limit);

        if (!result.allowed()) {
            metrics.recordBlocked(clientId, result.algorithm());
            response.setStatus(429);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"rate limit exceeded\"}");
            return;
        }

        metrics.recordAllowed(clientId, result.algorithm());
        chain.doFilter(request, response);
    }

    private String resolveClientId(HttpServletRequest request) {
        String header = request.getHeader("X-Client-Id");
        return header != null ? header : request.getRemoteAddr();
    }

    // otherwise Prometheus scraping /actuator/prometheus counts against its own limit
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator");
    }
}
