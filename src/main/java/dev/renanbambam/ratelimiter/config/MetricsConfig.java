package dev.renanbambam.ratelimiter.config;

import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.config.MeterFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

// so metrics from this service are identifiable in a shared Prometheus instance
@Configuration
public class MetricsConfig {

    @Bean
    public MeterFilter rateLimiterCommonTags() {
        return MeterFilter.commonTags(List.of(Tag.of("application", "distributed-rate-limiter")));
    }
}
