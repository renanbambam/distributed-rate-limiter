package dev.renanbambam.ratelimiter.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

// tagged by client and algorithm so a dashboard can answer "who's getting
// throttled" without querying Redis directly
@Component
public class RateLimiterMetrics {

    private static final String ALLOWED_METRIC = "rate_limiter.requests.allowed";
    private static final String BLOCKED_METRIC = "rate_limiter.requests.blocked";

    private final MeterRegistry registry;

    public RateLimiterMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordAllowed(String clientId, String algorithm) {
        counter(ALLOWED_METRIC, clientId, algorithm).increment();
    }

    public void recordBlocked(String clientId, String algorithm) {
        counter(BLOCKED_METRIC, clientId, algorithm).increment();
    }

    private Counter counter(String metricName, String clientId, String algorithm) {
        return Counter.builder(metricName)
                .tag("client", clientId)
                .tag("algorithm", algorithm)
                .register(registry);
    }
}
