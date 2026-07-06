package dev.renanbambam.ratelimiter.adapter.rest;

import dev.renanbambam.ratelimiter.domain.model.RateLimitResult;
import dev.renanbambam.ratelimiter.domain.port.RateLimiter;
import dev.renanbambam.ratelimiter.metrics.RateLimiterMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DemoController.class)
class RateLimitFilterTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RateLimiter rateLimiter;

    @MockBean
    private RateLimiterMetrics metrics;

    @Test
    void allowedRequestPassesThroughWithHeaders() throws Exception {
        when(rateLimiter.isAllowed(any(), any()))
                .thenReturn(RateLimitResult.allowed(7, 1_700_000_060L, "token-bucket"));

        mockMvc.perform(get("/api/demo"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-RateLimit-Remaining", "7"))
                .andExpect(header().string("X-RateLimit-Algorithm", "token-bucket"));

        verify(metrics).recordAllowed(any(), org.mockito.ArgumentMatchers.eq("token-bucket"));
    }

    @Test
    void blockedRequestReturns429() throws Exception {
        when(rateLimiter.isAllowed(any(), any()))
                .thenReturn(RateLimitResult.blocked(1_700_000_060L, "fixed-window"));

        mockMvc.perform(get("/api/demo"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("X-RateLimit-Remaining", "0"))
                .andExpect(header().string("X-RateLimit-Algorithm", "fixed-window"));

        verify(metrics).recordBlocked(any(), org.mockito.ArgumentMatchers.eq("fixed-window"));
    }
}
