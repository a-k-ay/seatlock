# Phase 3 — Data Layer & Concurrency

**Status:** Complete  
**Dates:** 2026-09-21 → 2026-09-22  
**Related ADRs:** ADR-005 (Tech Stack), ADR-006 (Seat Hold Concurrency)

## Purpose

Phase 3 establishes SeatLock's data foundation and the concurrency 
mechanisms that make its correctness guarantees possible. Everything 
downstream — the confirm-booking flow, the expiry worker, the 
reconciliation queries, the observability layer — depends on the 
schema, indexes, isolation policy, and idempotency-store design 
locked here.

## What Phase 3 Established

- The full relational schema for all six entities.
- The concurrency mechanisms enforcing "one seat, one buyer" at the 
  database level.
- The transaction isolation policy for business flows and reporting.
- The Redis idempotency store contract that makes payments safe to 
  retry.

## Data Dictionary

The following six entities form SeatLock's storage layer. Money is 
always stored as `BIGINT` in the smallest currency unit (paise for 
INR, cents for USD) with a paired `VARCHAR(3)` currency code. Never 
`DECIMAL`, never `FLOAT`.

### `users`
- `id`: UUID, primary key
- `email`: string, unique
- `password_hash`: string
- `created_at`, `updated_at`: timestamps

### `events`
- `id`: UUID, primary key
- `name`: string
- `venue`: string
- `start_time`, `end_time`: timestamps with timezone
- `status`: enum (`UPCOMING`, `ON_SALE`, `SOLD_OUT`, `ENDED`, `CANCELLED`)
- `created_at`, `updated_at`: timestamps

### `seats`
- `id`: UUID, primary key
- `event_id`: FK → events.id
- `section`, `row`, `seat_number`: strings
- `base_price_amount`: BIGINT (paise/cents)
- `base_price_currency`: VARCHAR(3)
- `created_at`: timestamp
- No `status` column. Availability is derived from holds and bookings 
  to avoid two-sources-of-truth drift.
- Unique constraint: `(event_id, section, row, seat_number)`.

### `holds`
- `id`: UUID, primary key
- `event_id`, `seat_id`, `user_id`: FKs
- `status`: enum (`ACTIVE`, `EXPIRED`, `CONFIRMED`)
- `price_at_hold_amount`: BIGINT — snapshot of price at the moment 
  the hold was created. Immutable once written.
- `price_at_hold_currency`: VARCHAR(3)
- `expires_at`: timestamp with timezone
- `created_at`, `updated_at`: timestamps
- **Partial unique index:** `UNIQUE (seat_id) WHERE status = 'ACTIVE'` 
  — the database-level guarantee against double-booking.

### `bookings`
- `id`: UUID, primary key
- `event_id`, `seat_id`, `user_id`, `hold_id`: FKs
- `status`: enum (`CONFIRMED`, `CANCELLED`, `REFUNDED`)
- `total_amount`: BIGINT (paise/cents)
- `currency`: VARCHAR(3)
- `created_at`, `updated_at`: timestamps
- Unique constraint: one booking per hold (`UNIQUE (hold_id)`).
- Partial unique index: `UNIQUE (seat_id) WHERE status = 'CONFIRMED'` 
  — belt-and-braces defense against double-bookings surviving to 
  confirmation.

### `payments`
- `id`: UUID, primary key
- `booking_id`: FK → bookings.id
- `amount`: BIGINT
- `currency`: VARCHAR(3)
- `status`: enum (`PENDING`, `SUCCESS`, `FAILED`, `REFUNDED`)
- `gateway_reference`: string (stubbed gateway's transaction ID)
- `idempotency_key`: string, unique — the client-supplied key. 
  Postgres is the permanent record; Redis is the fast lookup.
- `created_at`, `updated_at`: timestamps

## Migrations

Flyway migration files are the source of truth for the schema. They 
run in order on application startup.

- `V1__init.sql` — pgcrypto extension, shared `set_updated_at()` 
  trigger function
- `V2__create_users.sql`
- `V3__create_events.sql`
- `V4__create_seats.sql`
- `V5__create_holds.sql` — includes the partial unique index
- `V6__create_bookings.sql` — includes the hold-uniqueness and 
  confirmed-seat partial indexes
- `V7__create_payments.sql` — includes the unique idempotency-key 
  constraint

The SQL for each file is committed to the repo under 
`src/main/resources/db/migration/` (Spring Boot's Flyway default 
location) when the project skeleton is stood up in Phase 8.

## Concurrency Policy (Recap of ADR-006)

Two mechanisms, one per lifecycle stage:

- **Create-hold race:** partial unique index on `holds`. Concurrent 
  inserts for the same seat produce exactly one winner; losers 
  receive `409 SEAT_UNAVAILABLE`. Correctness is guaranteed by the 
  database, not the application layer.
- **Confirm-booking flow:** `SELECT ... FOR UPDATE` on the hold row. 
  Blocks the expiry worker and any other confirmation attempt while 
  the payment transaction runs.

Holds are append-only. Rows transition through statuses 
(`ACTIVE` → `EXPIRED` or `CONFIRMED`) but are never deleted. The 
partial index automatically exempts non-`ACTIVE` rows, so reusing a 
seat after expiry is just another insert.

## Transaction Isolation Policy

- **READ COMMITTED** (Postgres default) for all business flows — 
  create hold, confirm booking, refund. Isolation is not the tool 
  preventing double-booking; row locks and unique constraints are. 
  READ COMMITTED gives the best throughput without weakening 
  correctness for these flows.
- **REPEATABLE READ** for reporting and reconciliation queries — 
  event stats, payment/booking reconciliation, "seats available at 
  time T." These need a consistent snapshot.
- **SERIALIZABLE is not used** unless a race is discovered that no 
  lock, constraint, or REPEATABLE READ can address. Its retry-storm 
  behavior under contention is a poor fit for SeatLock's write 
  profile.

## Redis Idempotency Store

The idempotency store makes retried payment requests safe.

### Key/value shape

KEY: idempotency:payments:<idempotency-key>
VALUE: {
"status_code": <int>,
"response_body": <serialized JSON>,
"request_hash": <SHA-256 of the request body>,
"created_at": <ISO-8601 timestamp>
}
TTL: 86400 seconds (24 hours)


### Lookup flow

1. Request arrives with `Idempotency-Key: <key>` and body `B`.
2. Compute `hash(B)`.
3. `GET idempotency:payments:<key>` from Redis.
   - Hit + matching hash → return stored status/body verbatim.
   - Hit + mismatched hash → `422 IDEMPOTENCY_KEY_REUSED`. This is 
     a client bug and must fail loudly, not silently.
   - Miss → proceed to process the payment.
4. On successful processing:
   - `SET idempotency:payments:<key> = { … } EX 86400`.
   - `INSERT INTO payments (..., idempotency_key = <key>)`. The 
     Postgres UNIQUE constraint is the permanent guard.

### Failure modes

- **Redis unavailable:** application falls back to Postgres 
  (`SELECT ... WHERE idempotency_key = ?`). Slower, still correct.
- **TTL expired but request retried after 24h:** Postgres UNIQUE 
  catches the second INSERT with `23505`. Application translates 
  that into "already processed" and returns the stored response 
  fetched from the DB.
- **Simultaneous identical requests:** whichever writes to Redis 
  first serves the cache; the other reads the same response back. 
  If they collide at the millisecond level, Postgres UNIQUE picks 
  a winner.

## What's Next — Phase 4: Reliability

Phase 4 turns the schema into a reliable system:

- The hold-expiry worker (background job that flips 
  `ACTIVE → EXPIRED` past `expires_at`).
- The confirm-booking flow end-to-end (idempotent payment + hold 
  consumption + booking creation, all in one transaction).
- Failure-mode analysis: what happens if the payment stub crashes 
  mid-flow, if Redis is down, if the DB rejects a commit.
- Retry semantics for external callers (which HTTP status codes are 
  safe to retry, per the Phase-2 idempotency contract).

## Open Items

- Data retention: no archive strategy for old holds/bookings/payments. 
  Acceptable at portfolio scale. Documented for future.
- Rate limiting: not addressed. Deferred to Phase 5 (Security).