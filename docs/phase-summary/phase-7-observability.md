# Phase 7 — Observability

**Status:** Complete
**Dates:** 2026-09-29 to 2026-09-30
**Related ADRs:** ADR-005 (Tech Stack)

## Purpose

Phase 7 turns SeatLock from "it works" into "you can *see* it work".
The domain logic proven in Phase 6 is invisible from outside unless
we surface health, structured logs, and metrics. This phase adds the
three legs of the observability stool: **health probes, structured
logging, and domain-specific metrics**.

Every backend engineer is asked "how do you know your service is
healthy in production?" This phase gives concrete answers.

## What Phase 7 Established

- `/actuator/health` returns a per-subsystem breakdown (DB, Redis,
  disk, ping) with `show-details=always`
- Kubernetes-style probes at `/actuator/health/readiness` (fails if
  DB or Redis are unreachable) and `/actuator/health/liveness`
  (only checks JVM viability)
- Structured JSON logging via `logstash-logback-encoder` in the
  `prod` profile; pretty console logging in dev
- A servlet filter (`MdcRequestContextFilter`) that assigns a UUID
  `requestId` to every incoming request and puts it in Logback MDC,
  along with `userId` when authenticated; both are threaded through
  every log line
- The requestId is also echoed on the `X-Request-Id` response header
  for client-side correlation
- Micrometer counters for the three domain lifecycle events:
  `seatlock.holds.created` (tagged `outcome=success|seat_unavailable`),
  `seatlock.holds.cancelled`, `seatlock.holds.expired`
- Prometheus scrape endpoint exposed at `/actuator/prometheus`

## Files Added

Under `src/main/java/com/seatlock/config`:
- `MdcRequestContextFilter` — extends `OncePerRequestFilter`, sets
  `requestId` and `userId` in MDC, clears in `finally`
- Updated `GlobalExceptionHandler` — injects `MeterRegistry` and
  increments the `seat_unavailable` counter on both
  `DataIntegrityViolationException` and `SeatsUnavailableException`
- Updated `SecurityConfig` — permits `/actuator/health/**` and
  `/actuator/prometheus` publicly

Under `src/main/resources`:
- `logback-spring.xml` — dual-appender config: pretty console for
  `!prod`, JSON via `LogstashEncoder` for `prod`

Under `src/main/java/com/seatlock/holds`:
- Updated `HoldService` — `MeterRegistry` injected, increments
  counters at the boundary of a real outcome (not on request receipt)
- Updated `HoldExpiryWorker` — increments `seatlock.holds.expired`
  by the batch size when the sweep finds stale rows

Configuration:
- `pom.xml` — added `logstash-logback-encoder` 8.0 and
  `micrometer-registry-prometheus`
- `application.properties` — actuator exposure list
  (`health,prometheus`), `show-details=always`, `probes.enabled=true`,
  readiness group includes `db,redis`, `springdoc.show-actuator=true`

## Health probes — the semantic distinction

- **Liveness** = "is the JVM alive?" — checked only against
  `livenessState`. A DB blip does *not* fail liveness. If liveness
  failed, an orchestrator would kill and restart the pod, but killing
  a healthy JVM because Postgres blipped is exactly the wrong response.
- **Readiness** = "is this instance ready to serve traffic *right
  now*?" — includes DB and Redis. If either is unreachable, the
  instance is removed from load balancer rotation until they come
  back. The JVM keeps running.

This distinction is why `management.endpoint.health.group.readiness.include`
lists `db,redis` but the liveness group intentionally does not.

## Request tracing — what MDC gives us

Every log line for a given HTTP request now looks like:

```
17:15:32.487 INFO  req=a1b2c3d4-... user=aae940d1-... com.seatlock.holds.HoldController - ...
```

When a user reports a bug ("my hold failed at 2:57pm"), they can be
asked for the `X-Request-Id` from the response headers. That single
UUID appears on every log line the request touched — filter, security
chain, controller, service, DB — from the moment it hit the server
until the response left. This is the difference between debugging
production by pattern-matching timestamps versus deterministic tracing.

The Postgres constraint violation logs — the ones proving ADR-006
under real load — are now attributed:
```
WARN req=72a91dc8... user=aae940d1... org.hibernate.orm.jdbc.error
  - ERROR: duplicate key value violates unique constraint
    "one_active_hold_per_seat"
```

## Metrics — why the outcome tag pattern

Rather than three separate counter names for created / conflicted /
duplicate-key, we use **one counter name with an `outcome` tag**:

```
seatlock_holds_created_total{outcome="success"} 12.0
seatlock_holds_created_total{outcome="seat_unavailable"} 3.0
```

This is the Prometheus best practice. It lets you graph success and
failure as two lines on one chart, compute failure rate as
`rate(...{outcome="seat_unavailable"}[5m]) / rate(...[5m])`, and add
new outcomes later without adding new counter names.

**Where counters are incremented:**
- **Success**: in `HoldService`, right before returning the hold —
  meaning the row is committed, not just requested
- **`seat_unavailable`**: in `GlobalExceptionHandler` — the single
  place that sees the failure exit path from both single and bulk
  hold attempts
- **Bulk sizing**: `createBulkHolds` increments by `results.size()`
  on success; the pre-check failure increments by the size of the
  unavailable list

The exception handler being the failure-counting site is a deliberate
architectural choice — the service can't count what it hasn't seen come
back, and mixing "increment failure counter" into service-layer
try/catches would muddy the pure-business-logic goal.

## Endpoints Summary

| Method | Path                          | Auth | Purpose                     |
|--------|-------------------------------|------|-----------------------------|
| GET    | /actuator/health              | none | Overall health + subsystems |
| GET    | /actuator/health/readiness    | none | k8s-style readiness probe   |
| GET    | /actuator/health/liveness     | none | k8s-style liveness probe    |
| GET    | /actuator/prometheus          | none | Prometheus scrape endpoint  |

## Configuration Reference

`application.properties`:

    management.endpoints.web.exposure.include=health,prometheus
    management.endpoint.health.show-details=always
    management.endpoint.health.probes.enabled=true
    management.endpoint.health.group.readiness.include=readinessState,db,redis
    springdoc.show-actuator=true

`logback-spring.xml`:

    <springProfile name="!prod"> → pretty console
    <springProfile name="prod">  → JSON via LogstashEncoder

## Explicit Non-Goals

- **OpenTelemetry / distributed tracing** — Micrometer + logs give
  us enough for a single-service app. Tracing shines across services;
  we have one
- **Log shipping** to Loki / Elastic / Datadog — logs stay on the
  VM; a real product would ship them, we don't
- **Grafana dashboards** — Prometheus format is emitted; hooking up
  Grafana is a follow-up
- **Alerting rules** — Prometheus AlertManager wiring is a follow-up
- **Custom histograms / SLOs** — Spring Boot's built-in
  `http_server_requests_seconds` covers HTTP-level latency; deeper
  SLO work is a follow-up

## Follow-Ups (Tracked, Deferred)

- Wire Prometheus + Grafana against the exposed endpoint (docker-compose
  addition) for a live dashboard demo
- Structured error codes on `WARN` lines so a log aggregator can build
  error-rate alerts without regex-matching messages
- OTel exporter as a stretch goal for the "trace a POST /holds
  through every layer" story
- `Timer` around `HoldService.createHold` for p95/p99 latency
  histograms (Micrometer supports it, we just haven't added)

## Validation Ledger

| Guarantee | Where proven |
|-----------|--------------|
| Health endpoints publicly reachable, subsystems break out | Browser test on `/actuator/health` with Postgres/Redis both UP |
| Readiness includes dependencies | `/actuator/health/readiness` shows db + redis; liveness shows only livenessState |
| Every log line carries requestId + userId | Log tail during Swagger request shows `req=<uuid> user=<uuid>` |
| Response header carries requestId | Curl `-i` shows `X-Request-Id: <uuid>` |
| Domain events counted | `curl /actuator/prometheus \| grep seatlock_` shows all three counter families |
| JSON logs in prod profile | `docker compose up` (which sets `SPRING_PROFILES_ACTIVE=prod`) emits JSON |

## What's Next — Phase 8: Deployment

The app is now observable. Phase 8 packages it and ships it: multi-stage
Dockerfile, docker-compose stack with Postgres + Redis + app, VPS
provisioning, Nginx reverse proxy, Let's Encrypt HTTPS. See ADR-009 for
the architecture rationale.