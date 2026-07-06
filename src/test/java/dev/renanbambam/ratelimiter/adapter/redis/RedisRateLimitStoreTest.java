package dev.renanbambam.ratelimiter.adapter.redis;

import dev.renanbambam.ratelimiter.domain.model.RateLimitDecision;
import dev.renanbambam.ratelimiter.domain.model.TokenBucketState;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

// runs the Lua scripts against a real Redis. The concurrency test is the one
// that matters - it's the only way to catch a script that looks atomic but isn't
class RedisRateLimitStoreTest {

    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static RedisRateLimitStore store;

    @BeforeAll
    static void startRedis() {
        REDIS.start();
        RedisStandaloneConfiguration configuration =
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory = new LettuceConnectionFactory(configuration);
        connectionFactory.afterPropertiesSet();
        StringRedisTemplate redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        store = new RedisRateLimitStore(redisTemplate);
    }

    @AfterAll
    static void stopRedis() {
        connectionFactory.destroy();
        REDIS.stop();
    }

    @Test
    void incrementAndExpireCountsAcrossCalls() {
        String key = "test:fixed:" + System.nanoTime();

        assertThat(store.incrementAndExpire(key, 60)).isEqualTo(1L);
        assertThat(store.incrementAndExpire(key, 60)).isEqualTo(2L);
        assertThat(store.incrementAndExpire(key, 60)).isEqualTo(3L);
    }

    @Test
    void consumeTokenDrainsAndRefillsBucket() {
        String key = "test:token-bucket:" + System.nanoTime();
        long now = System.currentTimeMillis() / 1000;

        TokenBucketState first = store.consumeToken(key, 2, now);
        TokenBucketState second = store.consumeToken(key, 2, now);
        TokenBucketState third = store.consumeToken(key, 2, now);

        assertThat(first.hasToken()).isTrue();
        assertThat(second.hasToken()).isTrue();
        assertThat(third.hasToken()).isFalse();

        TokenBucketState refilled = store.consumeToken(key, 2, now + 2);
        assertThat(refilled.hasToken()).isTrue();
    }

    @Test
    void slidingWindowLogRejectsOnceLimitReached() {
        String key = "test:sliding-log:" + System.nanoTime();
        long now = System.currentTimeMillis() / 1000;

        RateLimitDecision first = store.checkSlidingWindowLog(key, now, 60, 2);
        RateLimitDecision second = store.checkSlidingWindowLog(key, now, 60, 2);
        RateLimitDecision third = store.checkSlidingWindowLog(key, now, 60, 2);

        assertThat(first.allowed()).isTrue();
        assertThat(second.allowed()).isTrue();
        assertThat(third.allowed()).isFalse();
    }

    @Test
    void slidingWindowCounterWeighsPreviousWindow() {
        String currentKey = "test:sliding-counter:current:" + System.nanoTime();
        String previousKey = "test:sliding-counter:previous:" + System.nanoTime();

        RateLimitDecision decision = store.checkSlidingWindowCounter(currentKey, previousKey, 5, 60, 0);

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.remaining()).isEqualTo(4);
    }

    @Test
    void incrementAndExpireIsAtomicUnderConcurrentLoad() throws InterruptedException {
        String key = "test:concurrent:" + System.nanoTime();
        int requestCount = 50;
        ExecutorService executor = Executors.newFixedThreadPool(10);

        List<Future<Long>> futures = IntStream.range(0, requestCount)
                .mapToObj(i -> executor.submit(() -> store.incrementAndExpire(key, 60)))
                .toList();

        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        List<Long> results = futures.stream().map(future -> {
            try {
                return future.get();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).toList();

        List<Long> expected = java.util.stream.LongStream.rangeClosed(1, requestCount).boxed().toList();
        assertThat(results).containsExactlyInAnyOrderElementsOf(expected);
    }
}
