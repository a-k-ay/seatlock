# SeatLock

A concurrent seat-booking backend that proves the "same seat sold twice" race condition cannot happen, at the database level, with zero application locks.

**🌐 Live demo**: [https://seatlock.duckdns.org/swagger-ui/index.html](https://seatlock.duckdns.org/swagger-ui/index.html)

**Test credentials**: `alice@seatlock.dev` / `alice123`

---

## What SeatLock is

SeatLock is a portfolio-grade Spring Boot backend that models the concurrent hold/booking problem BookMyShow-style ticketing systems face: when a popular event goes on sale, thousands of users try to grab the same seats in the same millisecond. If two of them succeed, someone shows up to a seat that isn't theirs.

The core technical claim: **at any moment, at most one ACTIVE hold exists per seat**, enforced by a Postgres partial unique index. No distributed locks, no advisory locks, no application-side coordination. The database is the single source of truth, and it can never lie.

This claim is **empirically validated** by a JUnit test that races 50 threads for one seat: exactly one wins, forty-nine get clean `DataIntegrityViolationException`s at the DB layer. See [ADR-006 → Validation Results](docs/adr/0006-seat-hold-concurrency.md#validation-results).

---

## Tech stack

- **Java 21** + **Spring Boot 4.1** + **Maven**
- **PostgreSQL 16** with a partial unique index for concurrency (`ACTIVE`-status only)
- **Redis 7** for idempotency-key storage (24h TTL, SHA-256 hashed request bodies)
- **Flyway** for schema versioning (V1-V8)
- **JWT** authentication (HS256, jjwt 0.12.6, 60-minute tokens)
- **Micrometer** + `/actuator/prometheus` for domain metrics
- **Logback + logstash-encoder** for structured JSON logs in production
- **Docker** + **Docker Compose** for local dev and deployment
- **Nginx** reverse proxy + **Let's Encrypt** TLS in production
- Deployed on **GCP e2-micro** (free tier)

---

## Quick start

**Prerequisites**: Docker + Docker Compose. Nothing else — no Java, no Maven, no Postgres needed locally.

```bash
git clone https://github.com/a-k-ay/seatlock.git
cd seatlock
docker compose up --build
```

First build takes ~5 minutes (downloads dependencies, compiles Java, packages jar). Subsequent starts are ~15 seconds.

Once "Started SeatlockApplication" appears in the logs:

- Swagger UI: [http://localhost:8080/swagger-ui/index.html](http://localhost:8080/swagger-ui/index.html)
- Health check: [http://localhost:8080/actuator/health](http://localhost:8080/actuator/health)
- Prometheus metrics: [http://localhost:8080/actuator/prometheus](http://localhost:8080/actuator/prometheus)

Login via `POST /auth/login` with `alice@seatlock.dev` / `alice123`, click Authorize in Swagger, then explore the endpoints.

---

## Architecture

```
                              ┌────────────────────────────────┐
       Internet               │  GCP e2-micro (Ubuntu 24.04)   │
                              │                                │
  ┌──────────────┐            │    ┌─────────┐                 │
  │  Browser /   │─── 443 ───▶│    │  Nginx  │                 │
  │  API client  │            │    │ (443)   │                 │
  └──────────────┘            │    └────┬────┘                 │
                              │         │ localhost:8080       │
                              │         ▼                      │
                              │    ┌─────────┐                 │
                              │    │ Spring  │                 │
                              │    │  Boot   │                 │
                              │    │  App    │                 │
                              │    └──┬───┬──┘                 │
                              │       │   │                    │
                              │       │   └───────┐            │
                              │       ▼           ▼            │
                              │  ┌─────────┐  ┌────────┐       │
                              │  │Postgres │  │ Redis  │       │
                              │  │   16    │  │   7    │       │
                              │  └─────────┘  └────────┘       │
                              │                                │
                              └────────────────────────────────┘
```

All three services (Spring app, Postgres, Redis) run as Docker containers on the same Compose network. Nginx handles TLS termination and reverse-proxies to the app. The app itself is not directly exposed to the internet.

---

## Key architectural decisions (ADRs)

- **[ADR-001: Modular Monolith](docs/adr/0001-modular-monolith.md)** — package-by-feature over microservices for portfolio scope
- **[ADR-002: Domain Model](docs/adr/0002-domain-model.md)** — events, seats, holds, bookings
- **[ADR-003: Checkout Flow](docs/adr/0003-checkout.md)** — hold → confirm → paid state machine
- **[ADR-004: Error Handling](docs/adr/0004-error-handling.md)** — structured `{"error": "CODE"}` responses
- **[ADR-005: Tech Stack](docs/adr/0005-tech-stack.md)** — Java 21, Spring Boot 4, Postgres, Redis
- **⭐ [ADR-006: Seat Hold Concurrency](docs/adr/0006-seat-hold-concurrency.md)** — partial unique index over row locks — **empirically validated**
- **[ADR-007: Booking Confirmation](docs/adr/0007-confirm-booking.md)** — hold-to-booking transition semantics
- **[ADR-008: JWT Authentication](docs/adr/0008-jwt-auth.md)** — stateless HS256 tokens, no session store
- **[ADR-009: Deployment Architecture](docs/adr/0009-deployment-architecture.md)** — Docker + Nginx + Let's Encrypt on VPS over PaaS

---

## Development phases

Each phase closes with a summary doc. If you want the tour, read them in order:

- [Phase 1: Blueprint](docs/phase-summary/phase-1-blueprint.md)
- [Phase 2: Docker + Database](docs/phase-summary/phase-2-docker.md)
- [Phase 3: Events](docs/phase-summary/phase-3-events.md)
- [Phase 4: Seats](docs/phase-summary/phase-4-seats.md)
- [Phase 5: Security (JWT auth)](docs/phase-summary/phase-5-security.md)
- **[Phase 6: Holds (the crown jewel)](docs/phase-summary/phase-6-holds.md)** — includes the concurrency test
- [Phase 7: Observability](docs/phase-summary/phase-7-observability.md) — health, structured logs, metrics
- [Phase 8: DevOps & Deployment](docs/phase-summary/phase-8-devops.md) — containers, VPS, Nginx, HTTPS

---

## Highlighted features

**POST /holds** — place a hold on a seat, atomic against the DB constraint:

```http
POST /holds
Authorization: Bearer <jwt>
Idempotency-Key: <uuid>  # optional; enables safe retry

{ "seatId": "..." }
```

Returns `201` with the hold, or `409 SEAT_UNAVAILABLE` if another user beat you to it. Same request with the same `Idempotency-Key` returns the cached response — safe to retry on network flakes.

**POST /holds/bulk** — hold up to 10 seats atomically. Pre-check reports **all** unavailable seat IDs at once; if any pre-check fails, the whole batch is rolled back.

**DELETE /holds/{id}** — cancel your own hold. Only the owner can cancel; only `ACTIVE` holds can be cancelled. Status flips to `CANCELLED`; the seat is instantly available for someone else (the partial unique index no longer sees the row).

**Background expiry worker** — a `@Scheduled` job runs every 30 seconds, using `FOR UPDATE SKIP LOCKED` to flip stale ACTIVE holds to EXPIRED. The `SKIP LOCKED` clause means multiple worker instances (future horizontal scaling) grab non-overlapping batches without waiting on each other.

**Observability out of the box** — `/actuator/health` breaks down DB + Redis reachability, structured JSON logs with a per-request UUID + userId in MDC, Prometheus counters at `/actuator/prometheus` (`seatlock_holds_created_total{outcome=success|seat_unavailable}`, `seatlock_holds_cancelled_total`, `seatlock_holds_expired_total`).

---

## Explicit non-goals

Deliberately not built — each is a talking point, not code:

- **Payment integration** — the domain stops at booking confirmation; a payment gateway would come next
- **Refund flow** — cancellation is refund-safe only for ACTIVE holds, not CONFIRMED bookings
- **WebSocket push** — clients poll instead
- **Refresh tokens** — access tokens are 60 minutes; a refresh flow is a follow-up
- **User registration** — three users are seeded at startup; production would add an `/auth/register` endpoint
- **Rate limiting on `/auth/login`** — noted for follow-up
- **Multi-instance HA** — code is ready (`SKIP LOCKED` on the expiry worker), only one instance runs today

---

## Repository layout

```
seatlock/
├── src/main/java/com/seatlock/
│   ├── auth/           # User, JWT, login endpoint
│   ├── events/         # Event entity + endpoints
│   ├── seats/          # Seat entity + endpoints
│   ├── holds/          # Hold, IdempotencyStore, expiry worker
│   └── config/         # Security, exception handling, filters
├── src/main/resources/
│   ├── db/migration/   # Flyway migrations V1-V8
│   ├── application.properties
│   └── logback-spring.xml
├── src/test/java/
│   └── com/seatlock/holds/HoldConcurrencyTest.java   # 50-thread race
├── docs/
│   ├── adr/            # Architecture Decision Records
│   ├── phase-summary/  # What each phase established
│   └── notes/          # Learning notes (Spring Boot, Docker, etc.)
├── Dockerfile          # Multi-stage; Alpine JRE runtime (~200MB)
├── docker-compose.yml  # Postgres + Redis + app
└── pom.xml
```

---

## Attribution

Built during a business analyst → backend engineer transition, with AI pairing for architectural decisions and debugging. Every decision is documented — the point isn't the code, it's the decision trail.
