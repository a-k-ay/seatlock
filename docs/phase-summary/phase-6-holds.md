# Phase 6 — Holds

**Status:** Complete
**Dates:** 2026-09-28 to 2026-09-29
**Related ADRs:** ADR-006 (Seat Hold Concurrency — VALIDATED), ADR-004 (Error Handling), ADR-003 (Checkout)

## Purpose

Phase 6 is the concurrency crown jewel of SeatLock. It turns a
single unused seat row into an atomic reservation that can be
placed, retried safely, released, expired automatically, and — most
importantly — protected against the classic "same seat sold twice"
race under real concurrent load.

Every downstream slice (booking, payment) depends on the guarantee
established here: **at any moment, at most one ACTIVE hold exists
per seat**, enforced at the database level with zero application
locks.

## What Phase 6 Established

- `POST /holds` creates a single hold with `Idempotency-Key` support.
- `POST /holds/bulk` creates up to 10 holds atomically with a
  pre-check that reports **all** unavailable seat IDs at once, plus
  idempotency.
- `DELETE /holds/{id}` cancels an ACTIVE hold via a soft status
  flip to `CANCELLED`.
- A `@Scheduled` background worker sweeps expired ACTIVE holds every
  30 seconds using `FOR UPDATE SKIP LOCKED` — prepared for horizontal
  scaling with zero code change.
- A JUnit concurrency test proves ADR-006 empirically: 50 threads
  racing for the same seat produce exactly one winner and 49 clean
  `DataIntegrityViolationException`s at the DB layer.

## Files Added

Under `com.seatlock.holds`:
- `Hold` (JPA entity, `@ManyToOne` LAZY to Event/Seat/User)
- `HoldStatus` (enum: ACTIVE, EXPIRED, CONFIRMED, CANCELLED)
- `HoldRepository` (JPA + native `@Query` with `FOR UPDATE SKIP LOCKED`)
- `HoldService` (create, bulk, cancel — pure business logic)
- `HoldController` (REST endpoints + idempotency wrapping)
- `CreateHoldRequest`, `CreateBulkHoldRequest` (records with Bean
  Validation)
- `HoldResponse` (record)
- `IdempotencyStore` (Redis-backed, SHA-256 hashed, 24h TTL)
- `SeatsUnavailableException` (carries `List<UUID>` of offenders)
- `HoldExpiryWorker` (`@Scheduled` every 30s, batch size 100)

Under `com.seatlock.config`:
- `JacksonConfig` (explicit `ObjectMapper` bean with `JavaTimeModule`
  — Spring Boot 4 no longer auto-provides one)
- Updated `GlobalExceptionHandler` (catches
  `DataIntegrityViolationException` → 409 SEAT_UNAVAILABLE and
  `SeatsUnavailableException` → 409 with seat ID list)

Under `com.seatlock`:
- `@EnableScheduling` on `SeatlockApplication`

Under `src/test/java/com/seatlock/holds`:
- `HoldConcurrencyTest` (50-thread race with
  `ExecutorService` + `CountDownLatch`)

Under `src/test/resources`:
- `application-test.properties`

Migrations:
- `V5__create_holds.sql` — holds table + partial unique index
- `V6`–`V7` — pre-existing supporting migrations
- `V8__add_cancelled_status.sql` — drops and re-adds
  `holds_status_check` to include `CANCELLED`

Configuration:
- `pom.xml` — added `spring-boot-starter-data-redis`, plus
  `-Duser.timezone=UTC` on both `spring-boot-maven-plugin` and
  `maven-surefire-plugin`
- `application.properties` — added `seatlock.holds.ttl-minutes=8`

## Hold Creation Flow — Single (POST /holds)

1. Client sends `POST /holds` with `{ seatId }` and optionally
   `Idempotency-Key: <uuid>`.
2. `HoldController` computes SHA-256 hash of the request body.
3. If `Idempotency-Key` present:
   - **Cache hit + matching hash** → return cached `HoldResponse` (201).
   - **Cache hit + mismatched hash** → throw 422
     `IDEMPOTENCY_KEY_REUSED` (a caller sent the same key with a
     different body — refuse rather than let them lose track).
   - **Cache miss** → proceed to service, then store `hash || json`
     in Redis under the key for 24h.
4. `HoldService.createHold`:
   - Resolves the current user from `SecurityContextHolder`.
   - Fetches seat (404 if missing).
   - Builds a `Hold` with `status = ACTIVE`, snapshot price, and
     `expiresAt = now + 8min`.
   - `saveAndFlush` — flushes immediately so the constraint check
     fires inside the same request, not at commit.
5. If Postgres raises unique constraint 23505 on
   `one_active_hold_per_seat` → `DataIntegrityViolationException` →
   `GlobalExceptionHandler` returns 409 `SEAT_UNAVAILABLE`.

## Hold Creation Flow — Bulk (POST /holds/bulk)

1. Client sends up to 10 `seatIds` and optionally `Idempotency-Key`.
2. Same idempotency logic as single, but response type is
   `List<HoldResponse>` (uses `TypeReference` for deserialization).
3. `HoldService.createBulkHolds`:
   - **Option B pre-check**: `findByStatusAndSeatIdIn(ACTIVE, seatIds)`
     — returns all seats already held by anyone.
   - If non-empty → throw `SeatsUnavailableException(unavailableIds)` →
     `GlobalExceptionHandler` returns 409 with
     `{ "error": "SEAT_UNAVAILABLE", "unavailableSeatIds": [...] }`.
     Caller retries once, seeing every problem seat, not one at a time.
   - Otherwise insert all in a loop inside one `@Transactional`.
   - Any late DB clash (TOCTOU race between pre-check and insert)
     rolls back the entire batch → generic 409 fallback.
4. Semantics: **all-or-nothing**. If seat #3 fails, seats #1 and #2
   are not held.

## Hold Cancellation Flow (DELETE /holds/{id})

1. `HoldService.cancelHold`:
   - Fetches hold (404 if missing).
   - Verifies `hold.user.id == currentUserId` (403 if not — owner-only).
   - Verifies `status == ACTIVE` (409 if not — `Hold is not active
     (current status: EXPIRED/CANCELLED/CONFIRMED)`).
   - Sets `status = CANCELLED` and saves.
2. Returns `204 No Content`.
3. Because the partial unique index only sees `WHERE status = 'ACTIVE'`,
   the moment the flip commits the seat is free for a new hold with
   zero cleanup needed.

## Hold Expiry Worker

- Runs every 30s via `@Scheduled(fixedDelayString = "30000")`.
- Executes an atomic native SQL in one round-trip:

```sql
WITH expiring AS (
    SELECT id FROM holds
    WHERE status = 'ACTIVE' AND expires_at < NOW()
    FOR UPDATE SKIP LOCKED
    LIMIT 100
)
UPDATE holds SET status = 'EXPIRED', updated_at = NOW()
WHERE id IN (SELECT id FROM expiring)
```

- `FOR UPDATE SKIP LOCKED` means multiple worker instances (future
  horizontal scaling for HA) grab non-overlapping batches instead of
  waiting on each other. Zero coordination overhead.
- Batch size 100 prevents pathological locks during flash events.
- Logs per-tick count only when non-zero (silent when idle).

## Concurrency Test — ADR-006 Validation

`HoldConcurrencyTest.only_one_thread_succeeds_when_50_race_for_same_seat`:

- `@SpringBootTest` with `test` profile.
- Seeds one event + one seat in `@BeforeEach`.
- 50 threads via `ExecutorService.newFixedThreadPool(50)`.
- `CountDownLatch(1)` starting gate — all threads block on
  `latch.await()` until main thread `countDown`s. Fires everyone
  simultaneously.
- Each thread sets its own `SecurityContextHolder` (UUID as principal)
  before calling `holdService.createHold`.
- Asserts:
  - Exactly **1** success.
  - Exactly **49** `DataIntegrityViolationException`s.
  - Zero unexpected failures.
  - Exactly **1** ACTIVE row in DB for that seat.
- Cleans up event, seat, and all holds on that seat in `@AfterEach`.

**Result**: consistently green in ~1–3s of actual test time. ADR-006 is
no longer a design claim — it's a receipt.

## Endpoints Summary

| Method | Path            | Success | Failure modes                                          |
|--------|-----------------|---------|--------------------------------------------------------|
| POST   | /holds          | 201     | 404 seat, 409 seat_unavailable, 422 idempotency_reused |
| POST   | /holds/bulk     | 201     | 404 seat, 409 with unavailableSeatIds list, 422 same   |
| DELETE | /holds/{id}     | 204     | 404 hold, 403 not owner, 409 not active                |

All protected — require `Authorization: Bearer <token>` from Phase 5.

## Key Design Decisions

- **Partial unique index over row locks**: `CREATE UNIQUE INDEX ...
  WHERE status = 'ACTIVE'` gives us atomic single-writer semantics for
  free. Postgres does the coordination; our app writes as if it's the
  only client.
- **Soft delete via status flip, not hard `DELETE`**: cancelled and
  expired holds stay in the table for audit. Status transition is the
  release mechanism.
- **All-or-nothing bulk**: no partial-success responses to reason
  about. Client sees "all held" or "none held with these offenders".
- **Idempotency at controller layer, not service**: keeps the service
  a pure function of `(request, currentUser)`. Retries and cache
  concerns live in the HTTP-facing edge.
- **Money as `BIGINT` paise**: no floats, no decimals, snapshotted
  onto the hold at creation time so later price changes don't shift
  what the user thought they were paying.
- **`FOR UPDATE SKIP LOCKED` on the expiry sweep**: single-instance
  today, scale-ready without a code change.

## Test Credentials

Same seeded users from Phase 5 — Alice, Bob, Admin.

## Configuration Reference

`application.properties`:

    seatlock.holds.ttl-minutes=8

`application-test.properties` (mirrors main, minus JWT-relevant knobs):

    spring.datasource.url=jdbc:postgresql://localhost:5433/seatlock
    spring.data.redis.host=localhost
    spring.data.redis.port=6379
    seatlock.holds.ttl-minutes=8

`pom.xml` (both plugins):

    <argLine>-Duser.timezone=UTC</argLine>

## Explicit Non-Goals

Deliberately not built in Phase 6 — each is a talking point, not code:

- **`GET /holds`** — caller already has the hold ID + `expiresAt` from
  the POST response; a follow-up GET would just echo. Seat-level
  availability belongs on `GET /seats` (via seat status), not `/holds`.
- **Per-user hold count limit** (e.g., "no more than 6 seats at once
  per user") — enforceable but out of scope; the bulk endpoint caps
  at 10 per request as a proxy.
- **Distributed lock manager, Redis-based mutex, or advisory locks** —
  the partial unique index removes the need.
- **Multi-instance expiry worker HA** — code is ready (`SKIP LOCKED`),
  but only one instance runs today. No config changes needed to scale.
- **WebSocket push for hold state changes** — polling model is
  acceptable for portfolio scope.
- **Hold extension** ("give me 2 more minutes") — either you check out
  in time or you don't. Explicit non-feature.

## Follow-Ups (Tracked, Deferred)

- Revert `seatlock.holds.ttl-minutes` in `application.properties` to
  production-suitable value if left at test-friendly `1`.
- Rename `priceAtHoldAmount` → `priceAtHoldMinorUnits` for consistency
  with the "paise, not rupees" money model.
- `createdAt` returning null on POST /seats response (Jackson +
  `@CreationTimestamp` interaction — untriaged).
- Configure `AuthenticationEntryPoint` from Phase 5 follow-up so
  invalid tokens return 401 instead of 403 (currently manifests as
  the surprising `CONSTRAINT_VIOLATION` responses when tokens expire
  mid-DELETE testing).
- Migrate `HoldConcurrencyTest` to Testcontainers for CI portability.
- Add Micrometer counters on hold creation, cancellation, expiry —
  overlaps with Phase 7.
- Consider per-event pooled TTL (some venues want a 3-min lock, others
  10-min) — externalize per-event instead of the global property.

## Validation Ledger

| Guarantee                                           | Where proven                                                       |
|-----------------------------------------------------|--------------------------------------------------------------------|
| No two ACTIVE holds per seat under concurrent load  | `HoldConcurrencyTest` — 50 threads → 1 win, 49 constraint failures |
| Soft-delete releases seat instantly                 | psql check after DELETE + successful re-hold POST                  |
| Expiry worker sweeps ghost-ACTIVE rows              | Log line "Hold expiry worker: expired N hold(s)" + DB row status   |
| Idempotency replay returns identical response       | Swagger three-case test (cached, mismatched hash, no key)          |
| Bulk pre-check reports all offenders in one shot    | Swagger test with mixed available + held seats                     |

## What's Next — Phase 7: Observability

The domain is proven. Now make it visible from outside:

- Micrometer counters on hold create/cancel/expire and their outcomes.
- Structured JSON logging with request IDs threaded through.
- `/actuator/health` including DB and Redis readiness.
- `/actuator/metrics` exposed selectively.
- Consider OpenTelemetry export as a stretch goal for the portfolio
  story (tracing a POST /holds through filter → controller → service →
  DB).