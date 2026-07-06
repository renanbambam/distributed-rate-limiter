# Projeto 4 — distributed-rate-limiter

**Stack:** Java 17 + Spring Boot 3 + Redis + Micrometer + Docker Compose  
**Repositório:** github.com/renanbambam/distributed-rate-limiter  
**Por que existe:** Rate limiting é o problema de sistemas distribuídos que aparece em toda entrevista de empresa grande. Ter uma implementação funcional com 4 algoritmos diferentes no GitHub é raro mesmo entre sêniors.

---

## O que o sistema faz

API REST que demonstra 4 algoritmos de rate limiting aplicados a requisições HTTP. Cada algoritmo é uma estratégia intercambiável. Redis mantém o estado distribuído — funciona igual com múltiplas instâncias do serviço rodando.

---

## Estrutura de diretórios

```
distributed-rate-limiter/
├── src/
│   ├── main/
│   │   └── java/dev/renanbambam/ratelimiter/
│   │       │
│   │       ├── RateLimiterApplication.java
│   │       │
│   │       ├── domain/
│   │       │   ├── model/
│   │       │   │   ├── RateLimitKey.java          # record: clientId + endpoint
│   │       │   │   ├── RateLimitResult.java       # record: allowed, remaining, resetAt
│   │       │   │   └── RateLimitConfig.java       # record: limit, windowSeconds
│   │       │   │
│   │       │   ├── port/
│   │       │   │   └── RateLimiter.java           # interface com método isAllowed(key, config)
│   │       │   │
│   │       │   └── algorithm/
│   │       │       ├── FixedWindowRateLimiter.java      # janela fixa de N req/segundo
│   │       │       ├── SlidingWindowLogRateLimiter.java # log de timestamps no Redis
│   │       │       ├── SlidingWindowCounterRateLimiter.java # dois buckets, interpolação
│   │       │       └── TokenBucketRateLimiter.java      # tokens recarregados gradualmente
│   │       │
│   │       ├── adapter/
│   │       │   ├── redis/
│   │       │   │   ├── RedisRateLimitStore.java   # operações atômicas no Redis (Lua scripts)
│   │       │   │   └── LuaScripts.java            # scripts Lua embutidos como constantes
│   │       │   │
│   │       │   └── rest/
│   │       │       ├── DemoController.java        # endpoint /api/demo pra testar cada algoritmo
│   │       │       ├── RateLimitFilter.java       # intercepta toda requisição e aplica limite
│   │       │       └── RateLimitHeaders.java      # monta X-RateLimit-* headers na resposta
│   │       │
│   │       ├── config/
│   │       │   ├── RedisConfig.java               # RedisTemplate com serialização
│   │       │   ├── RateLimiterConfig.java         # qual algoritmo usar (property: rate-limiter.algorithm)
│   │       │   └── MetricsConfig.java             # counters e gauges do Micrometer
│   │       │
│   │       └── metrics/
│   │           └── RateLimiterMetrics.java        # registra allowed/blocked por cliente e algoritmo
│   │
│   └── test/
│       └── java/dev/renanbambam/ratelimiter/
│           ├── domain/algorithm/
│           │   ├── FixedWindowRateLimiterTest.java
│           │   ├── SlidingWindowLogRateLimiterTest.java
│           │   ├── SlidingWindowCounterRateLimiterTest.java
│           │   └── TokenBucketRateLimiterTest.java
│           ├── adapter/redis/
│           │   └── RedisRateLimitStoreTest.java   # usa Testcontainers Redis
│           └── adapter/rest/
│               └── RateLimitFilterTest.java       # @WebMvcTest
│
├── docker/
│   └── docker-compose.yml    # app + redis + prometheus + grafana
│
├── scripts/
│   └── load-test.sh          # envia 50 requests seguidos pra ver o throttling
│
├── .github/workflows/ci.yml
├── build.gradle.kts
├── .gitignore
└── README.md
```

---

## Especificação dos arquivos principais

### domain/model/RateLimitKey.java
```java
public record RateLimitKey(String clientId, String endpoint) {
    public String toRedisKey(String algorithm) {
        return "rl:%s:%s:%s".formatted(algorithm, clientId, endpoint);
    }
}
```

### domain/model/RateLimitResult.java
```java
public record RateLimitResult(
    boolean allowed,
    int remaining,
    long resetAtEpochSecond,
    String algorithm
) {
    public static RateLimitResult allowed(int remaining, long resetAt, String algorithm) {
        return new RateLimitResult(true, remaining, resetAt, algorithm);
    }
    public static RateLimitResult blocked(long resetAt, String algorithm) {
        return new RateLimitResult(false, 0, resetAt, algorithm);
    }
}
```

### domain/port/RateLimiter.java
```java
public interface RateLimiter {
    RateLimitResult isAllowed(RateLimitKey key, RateLimitConfig config);
    String algorithmName();
}
```

### domain/algorithm/FixedWindowRateLimiter.java
```java
// janela fixa: conta req no segundo atual, reseta no próximo
public class FixedWindowRateLimiter implements RateLimiter {

    private final RedisRateLimitStore store;

    public FixedWindowRateLimiter(RedisRateLimitStore store) {
        this.store = store;
    }

    @Override
    public RateLimitResult isAllowed(RateLimitKey key, RateLimitConfig config) {
        long windowStart = Instant.now().getEpochSecond();
        String redisKey = key.toRedisKey("fixed") + ":" + windowStart;

        long count = store.incrementAndExpire(redisKey, config.windowSeconds());
        long resetAt = windowStart + config.windowSeconds();

        if (count > config.limit()) {
            return RateLimitResult.blocked(resetAt, algorithmName());
        }
        return RateLimitResult.allowed((int)(config.limit() - count), resetAt, algorithmName());
    }

    @Override
    public String algorithmName() { return "fixed-window"; }
}
```

### domain/algorithm/TokenBucketRateLimiter.java
```java
// bucket com capacidade máxima; tokens são adicionados a cada segundo
public class TokenBucketRateLimiter implements RateLimiter {

    private final RedisRateLimitStore store;

    public TokenBucketRateLimiter(RedisRateLimitStore store) {
        this.store = store;
    }

    @Override
    public RateLimitResult isAllowed(RateLimitKey key, RateLimitConfig config) {
        String redisKey = key.toRedisKey("token-bucket");
        long now = Instant.now().getEpochSecond();

        // Lua script atômico: calcula tokens disponíveis baseado no tempo desde last_refill
        TokenBucketState state = store.consumeToken(redisKey, config.limit(), now);

        long resetAt = now + 1; // próximo refill em ~1 segundo
        if (!state.hasToken()) {
            return RateLimitResult.blocked(resetAt, algorithmName());
        }
        return RateLimitResult.allowed(state.remaining(), resetAt, algorithmName());
    }

    @Override
    public String algorithmName() { return "token-bucket"; }
}
```

### adapter/redis/LuaScripts.java
```java
// scripts Lua garantem atomicidade — Redis executa como transação
public final class LuaScripts {

    private LuaScripts() {}

    // incr + expire atômico (Fixed Window e Sliding Window Counter)
    public static final String INCREMENT_AND_EXPIRE = """
        local count = redis.call('INCR', KEYS[1])
        if count == 1 then
            redis.call('EXPIRE', KEYS[1], ARGV[1])
        end
        return count
        """;

    // Token Bucket: lê tokens e last_refill, calcula novo estado, persiste
    public static final String CONSUME_TOKEN = """
        local key = KEYS[1]
        local capacity = tonumber(ARGV[1])
        local now = tonumber(ARGV[2])

        local data = redis.call('HMGET', key, 'tokens', 'last_refill')
        local tokens = tonumber(data[1]) or capacity
        local last_refill = tonumber(data[2]) or now

        local elapsed = now - last_refill
        local refilled = math.min(capacity, tokens + elapsed)

        if refilled < 1 then
            return {0, math.floor(refilled)}
        end

        local remaining = math.floor(refilled - 1)
        redis.call('HMSET', key, 'tokens', remaining, 'last_refill', now)
        redis.call('EXPIRE', key, 60)
        return {1, remaining}
        """;

    // Sliding Window Log: ZADD + ZREMRANGEBYSCORE + ZCARD atômico
    public static final String SLIDING_WINDOW_LOG = """
        local key = KEYS[1]
        local now = tonumber(ARGV[1])
        local window = tonumber(ARGV[2])
        local limit = tonumber(ARGV[3])

        redis.call('ZREMRANGEBYSCORE', key, '-inf', now - window)
        local count = redis.call('ZCARD', key)

        if count >= limit then
            return {0, 0}
        end

        redis.call('ZADD', key, now, now .. math.random())
        redis.call('EXPIRE', key, window)
        return {1, limit - count - 1}
        """;
}
```

### adapter/rest/RateLimitFilter.java
```java
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
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws IOException, ServletException {
        var clientId = resolveClientId(request);
        var key = new RateLimitKey(clientId, request.getRequestURI());
        var config = new RateLimitConfig(limit, windowSeconds);

        var result = rateLimiter.isAllowed(key, config);

        response.setHeader("X-RateLimit-Limit", String.valueOf(limit));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(result.remaining()));
        response.setHeader("X-RateLimit-Reset", String.valueOf(result.resetAtEpochSecond()));
        response.setHeader("X-RateLimit-Algorithm", result.algorithm());

        if (!result.allowed()) {
            metrics.recordBlocked(clientId, result.algorithm());
            response.setStatus(429);
            response.getWriter().write("{\"error\":\"rate limit exceeded\"}");
            return;
        }

        metrics.recordAllowed(clientId, result.algorithm());
        chain.doFilter(request, response);
    }

    private String resolveClientId(HttpServletRequest request) {
        // em produção viria do JWT; aqui usa IP ou header X-Client-Id
        var header = request.getHeader("X-Client-Id");
        return header != null ? header : request.getRemoteAddr();
    }
}
```

### config/RateLimiterConfig.java
```java
@Configuration
public class RateLimiterConfig {

    @Value("${rate-limiter.algorithm:token-bucket}")
    private String algorithm;

    @Bean
    public RateLimiter rateLimiter(RedisRateLimitStore store) {
        return switch (algorithm) {
            case "fixed-window"            -> new FixedWindowRateLimiter(store);
            case "sliding-window-log"      -> new SlidingWindowLogRateLimiter(store);
            case "sliding-window-counter"  -> new SlidingWindowCounterRateLimiter(store);
            case "token-bucket"            -> new TokenBucketRateLimiter(store);
            default -> throw new IllegalArgumentException("algoritmo desconhecido: " + algorithm);
        };
    }
}
```

---

## docker-compose.yml

```yaml
services:
  redis:
    image: redis:7-alpine
    ports: ["6379:6379"]
    command: redis-server --maxmemory 100mb --maxmemory-policy allkeys-lru

  app:
    build: .
    depends_on: [redis]
    ports: ["8080:8080"]
    environment:
      SPRING_DATA_REDIS_HOST: redis
      RATE_LIMITER_ALGORITHM: token-bucket  # troca pra testar outros
      RATE_LIMITER_LIMIT: 10
      RATE_LIMITER_WINDOW_SECONDS: 60

  prometheus:
    image: prom/prometheus:v2.47.0
    volumes:
      - ./docker/prometheus.yml:/etc/prometheus/prometheus.yml
    ports: ["9090:9090"]
```

---

## scripts/load-test.sh

```bash
#!/bin/bash
# envia 15 requests com mesmo clientId pra ver throttling a partir do 11
for i in $(seq 1 15); do
  response=$(curl -s -w "\n%{http_code}" \
    -H "X-Client-Id: test-client" \
    http://localhost:8080/api/demo)
  
  body=$(echo "$response" | head -1)
  status=$(echo "$response" | tail -1)
  remaining=$(curl -s -I -H "X-Client-Id: test-client" http://localhost:8080/api/demo \
    | grep "X-RateLimit-Remaining" | awk '{print $2}')
  
  echo "req $i → HTTP $status | remaining: $remaining"
done
```

---

## README.md

```markdown
# distributed-rate-limiter

Rate limiter distribuído com 4 algoritmos implementados em Java 17 + Redis.

Built this after reading about rate limiting in system design interviews and
realizing most examples only show the concept — not what the tradeoffs between
algorithms actually look like in practice. The interesting part was writing the
Lua scripts: Redis executes them atomically, which is the only way to avoid
race conditions in a distributed setup without distributed locks.

## Algorithms

| Algorithm | Behavior | Tradeoff |
|-----------|----------|----------|
| Fixed Window | Resets counter every N seconds | Burst at window boundary |
| Sliding Window Log | Exact timestamps in a sorted set | Higher memory per client |
| Sliding Window Counter | Interpolates two fixed windows | ~1% error rate at edges |
| Token Bucket | Gradual token refill | Smoothest for burst traffic |

Switch algorithms at runtime:

    RATE_LIMITER_ALGORITHM=token-bucket  # or: fixed-window, sliding-window-log, sliding-window-counter

## Stack

Java 17 · Spring Boot 3 · Redis 7 · Micrometer · Docker Compose

## Running

    docker compose up -d
    ./scripts/load-test.sh  # sends 15 requests, shows throttling kick in

## Response headers

    X-RateLimit-Limit: 10
    X-RateLimit-Remaining: 7
    X-RateLimit-Reset: 1704067260
    X-RateLimit-Algorithm: token-bucket

## Architecture

Domain has zero Redis or Spring dependencies. Each algorithm implements
RateLimiter interface. Redis operations use Lua scripts for atomicity —
increment-and-expire, token bucket state, sliding window log — embedded as
string constants so they're always in sync with the code.

## Known limitations

- No persistence across Redis restart (acceptable — rate limits are ephemeral)
- Client identification uses X-Client-Id header or IP; production would use JWT claims
- No distributed tracing to correlate blocked requests across instances
- Sliding Window Log memory grows with request volume — Redis TTL bounds it, but
  high-traffic clients still use more memory than the other algorithms

## Tests

    ./gradlew test

Unit tests cover each algorithm with a mock store. Integration test
(Testcontainers Redis) verifies atomicity under concurrent load.
```

---

## Commits sugeridos

```
feat: modelo de domínio e interface RateLimiter
feat: Fixed Window com Lua script atômico
feat: Sliding Window Log e Counter
feat: Token Bucket com estado no Redis
feat: filtro HTTP e headers X-RateLimit-*
feat: métricas Micrometer por algoritmo e cliente
feat: configuração por property (troca algoritmo sem recompilar)
chore: docker-compose com Redis e Prometheus
test: testes unitários dos 4 algoritmos
test: integração com Testcontainers Redis
docs: README com tabela de trade-offs
```
