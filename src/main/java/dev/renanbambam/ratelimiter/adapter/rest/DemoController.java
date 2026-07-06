package dev.renanbambam.ratelimiter.adapter.rest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

// the rate limit decision already happened in RateLimitFilter by the time
// this runs - this just needs to exist so load-test.sh has something to hit
@RestController
public class DemoController {

    @GetMapping("/api/demo")
    public Map<String, Object> demo() {
        return Map.of(
                "message", "request allowed",
                "timestamp", Instant.now().toString()
        );
    }
}
