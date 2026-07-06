# distributed-rate-limiter

A rate limiter for HTTP APIs with four interchangeable algorithms, backed by
Redis so it works the same way whether you're running one instance or ten.

I built this after one too many system design interviews where rate limiting
comes up and the answer is "token bucket, next question." That's fine for a
whiteboard, but I wanted to actually see the four common algorithms behave
differently under the same load, with real Redis state instead of an
in-memory map that only works on a single node.

The interesting part was the Lua scripts. Every algorithm here needs a
read-modify-write against Redis, and if you do that as separate commands from
the application, two concurrent requests can both read "9 out of 10 used" and
both get let through. Redis runs a Lua script as a single atomic step, so the
check-and-increment happens without a distributed lock. That's the whole
trick, and it's the reason `LuaScripts.java` exists as its own file instead of
being inlined wherever it's used.

## Algorithms

| Algorithm | How it works | Tradeoff |
|---|---|---|
| Fixed Window | One counter per window, `INCR` + `EXPIRE` on first write | Cheapest, but a client can send `limit` requests right before a window boundary and `limit` more right after, a 2x burst |
| Sliding Window Log | Every request timestamp stored in a Redis sorted set, trimmed on each check | No boundary burst, exact count, but memory grows with request volume per client instead of staying flat |
| Sliding Window Counter | Two fixed-window counters, previous window weighted by overlap with the current one | Fixed memory like Fixed Window, smooths out the boundary burst, but it's an approximation that assumes even request distribution within a window |
| Token Bucket | Bucket holds up to `limit` tokens, refills one per second | Best for legitimate bursty traffic (a client can spend a saved-up bucket in one shot), but tuning capacity vs. refill rate takes some thought for non-uniform traffic patterns |

Switch between them with a property, no rebuild needed:

```
rate-limiter.algorithm=token-bucket   # or: fixed-window, sliding-window-log, sliding-window-counter
```

or the equivalent environment variable, `RATE_LIMITER_ALGORITHM`.

## Stack

Java 17, Spring Boot 3, Redis 7, Micrometer, Docker Compose.

## Architecture

The domain package (`domain/algorithm`, `domain/model`, `domain/port`) has no
Spring or Redis imports, just plain Java records and a `RateLimiter` interface
with four implementations. Each algorithm depends on `RedisRateLimitStore`,
which is the only class that talks to Redis directly. Wiring (which algorithm
is active, how the store is constructed) lives in `config/`, so the domain
doesn't know Spring exists.

`RateLimitFilter` sits in front of every request (except `/actuator/**`,
which would otherwise get throttled by the same rules it's meant to expose
metrics for), applies whichever algorithm is configured, and writes
`X-RateLimit-*` headers regardless of the outcome:

```
X-RateLimit-Limit: 10
X-RateLimit-Remaining: 7
X-RateLimit-Reset: 1704067260
X-RateLimit-Algorithm: token-bucket
```

A blocked request gets a 429 with a JSON body and never reaches the
controller.

## Running it

```
cd docker
docker compose up -d
../scripts/load-test.sh
```

The load test script fires 15 requests at `/api/demo` with the same client
id so you can watch the configured algorithm start returning 429s once the
default limit of 10 is hit.

To compare algorithms, change `RATE_LIMITER_ALGORITHM` in
`docker/docker-compose.yml` and restart the `app` service. No code change,
no rebuild.

## Tests

```
./gradlew test
```

Each algorithm has a unit test against a mocked `RedisRateLimitStore`, checking
the decision logic (allowed/blocked, remaining count) without touching a real
Redis. `RedisRateLimitStoreTest` runs the actual Lua scripts against a
Testcontainers Redis instance, including a concurrency test that fires 50
increments from 10 threads at the same key and asserts the results are
exactly `1..50` with no duplicates or gaps. That's the test that would fail
if a script weren't really atomic. `RateLimitFilterTest` is a
`@WebMvcTest` that checks the filter sets the right headers and status code
for both outcomes.

Running the Testcontainers test requires a working Docker daemon.

## Known limitations

- Client identification falls back to `X-Client-Id` header or the request's
  remote address. There's no auth here, so a client can pick any id it wants
  and get its own quota. In production this would come from a JWT claim or
  API key, not a client-supplied header.
- Per-client Micrometer tags (`client`, `algorithm`) mean a high number of
  distinct clients turns into a high number of distinct time series. Fine for
  a demo or a service with a bounded set of known clients; I'd bucket or drop
  the client tag before pointing this at anonymous public traffic.
- Sliding Window Log memory is bounded by the Redis TTL on each key, but a
  client sending far more requests than its limit within one window still
  grows that key's sorted set proportionally to attempts, not to the limit.
- No distributed tracing, so correlating a specific blocked request across
  multiple app instances means cross-referencing timestamps by hand.
- Redis is a single point of failure here. If it's down, the filter has
  nothing to check against. I didn't add a fail-open/fail-closed toggle;
  right now a Redis outage surfaces as a 500, which is arguably the wrong
  default for a rate limiter (you probably want to fail open).
- The Sliding Window Counter's "remaining" count is an estimate derived from
  the weighted formula, not an exact number of requests left before the
  limit. Don't build client-facing guarantees on its precision.
