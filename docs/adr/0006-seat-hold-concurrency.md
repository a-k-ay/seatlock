# ADR-006: Seat Hold Concurrency

**Status:** Accepted  
**Date:** 2026-09-21

## Context

SeatLock's core promise is one-seat-one-buyer under load. When two 
users try to hold the same seat at the same millisecond, exactly one 
must win; the other must receive a clean "unavailable" response. The 
system must also support:

- Time-boxed holds (a hold auto-releases after a TTL, e.g. 8 minutes) 
  so seats do not sit locked when users abandon their session.
- Full audit history of every hold ever placed on every seat.
- An atomic "confirm booking" flow that reads the hold, verifies it, 
  inserts a booking, and marks the hold as consumed — without race 
  conditions between the confirm flow and the hold-expiry worker.
- Predictable behavior under high contention (many users, hot seat).

The choice of concurrency mechanism at each lifecycle stage shapes 
the schema, indexes, transaction isolation, and every downstream flow 
in Phases 4–6.

## Decision

Seat-hold concurrency is split across two lifecycle stages, each using 
a different mechanism.

**Create-hold race (double-booking prevention):**

Enforced by a Postgres **partial unique index** on the `holds` table:

```sql
CREATE UNIQUE INDEX one_active_hold_per_seat
  ON holds (seat_id)
  WHERE status = 'ACTIVE';
  
  
Two concurrent inserts for the same seat: one succeeds, the other
fails with SQL error code 23505 (unique violation), which the
application layer translates to a 409 CONFLICT response with error
code SEAT_UNAVAILABLE.

Confirm-booking flow (transactional consistency during payment):

Enforced by pessimistic row locking:

SELECT * FROM holds WHERE id = ? FOR UPDATE;
The lock is acquired at the start of the confirm transaction, blocking
any concurrent state change to the same hold (including the expiry
worker) until the transaction commits or rolls back.

Hold lifecycle:

Holds are append-only. Rows are never deleted. Status transitions:
ACTIVE → EXPIRED (by TTL worker) or ACTIVE → CONFIRMED (by
successful booking). Because the partial unique index is scoped to
status = 'ACTIVE', expired and confirmed holds are automatically
exempt from the uniqueness constraint, and new holds can be created
on the same seat freely once the prior hold is no longer active.

Expiry:

A background worker (specified in Phase 4) periodically flips
ACTIVE holds past their expires_at to EXPIRED. A lazy inline
check during hold creation covers the gap when the worker is delayed
or offline.

Alternatives Considered
Option A — Pessimistic locking (SELECT ... FOR UPDATE) on the
seat row for the create-hold race
Rejected as the primary mechanism. Holds transactions that may include
downstream work (idempotency checks, event lookups). Under contention,
threads queue up on the seat row; throughput collapses. Row lock also
lives entirely in application discipline — one missing FOR UPDATE
and correctness is lost.

Retained for the confirm-booking flow, where the transaction is
short, the invariant spans multiple rows, and blocking behavior is
acceptable.

Option B — Optimistic locking (version column)
Rejected. Under high contention on hot seats — the exact scenario
SeatLock exists to handle — hundreds of concurrent transactions would
race, and all but one would fail their version check and retry. This
produces retry storms and degrades throughput precisely when the
system is most stressed. The retry loop also lives in application
code, adding surface area for bugs.

Option C — Partial unique index (chosen for create-hold race)
Selected. Enforces the invariant at the database level, cannot be
bypassed by buggy application code, holds no locks between statements,
and composes cleanly with the append-only hold lifecycle.

Application-level distributed lock (Redis SETNX, ZooKeeper, etc.)
Rejected as overkill. SeatLock is a single-database monolith (ADR-001).
A distributed lock would move the invariant out of the database,
where it is currently enforceable declaratively. Adds an operational
dependency for no correctness gain.

Deleting expired holds rather than status-transitioning
Rejected. Destroys the audit trail, complicates forensic queries
("who held seat A5 during last Friday's incident?"), and offers no
functional benefit given that the partial index already exempts
non-ACTIVE rows from the constraint.

Consequences
Positive
Double-booking is prevented at the database layer, not in
application code. Even a buggy service cannot oversell.
No blocking during the create-hold race; throughput remains high
under contention.
Full audit trail of every hold, preserved by the append-only pattern.
Expired holds automatically fall out of the uniqueness constraint
with no special handling.
The confirm-booking flow is protected by an explicit row lock,
keeping the "paid but crashed" story clean when combined with
idempotency (Phase 3 continues here).
Each mechanism is used where it fits best: partial index for a
simple single-row invariant under high contention, pessimistic lock
for a multi-row transactional invariant on a warm row.
Negative
Partial unique indexes are Postgres-specific. MySQL requires
workarounds. This tightens the coupling to Postgres, already an
accepted choice per ADR-005.
Two concurrency mechanisms in one service means slightly more
mental overhead for readers. Mitigated by clear separation between
the hold-creation service and the booking-confirmation service.
The application layer must catch SQL error code 23505 and
translate it to a domain error. This is a well-understood pattern
but is one more thing that must be tested.
Expiry correctness depends on the TTL worker (or the lazy inline
check) running reliably. Failure mode analysis lives in Phase 4.
Neutral
The holds table grows indefinitely without a retention policy.
Not a concern at portfolio scale; would require an archival
strategy at production scale.
The partial index is only useful because status is a small,
low-cardinality enum with a hot value (ACTIVE) and cold values
(EXPIRED, CONFIRMED). This is by design.


---

## Validation Results

**Status:** Empirically validated
**Validated on:** 2026-09-29
**Test:** `src/test/java/com/seatlock/holds/HoldConcurrencyTest.java`
**Method:** `only_one_thread_succeeds_when_50_race_for_same_seat`

### What the test does

- Seeds one event, one seat, one user.
- Spawns 50 threads via `ExecutorService.newFixedThreadPool(50)`.
- Uses a `CountDownLatch(1)` as a starting gate so all 50 threads block
  on `await()` until the main thread `countDown`s. This produces true
  simultaneous entry into `HoldService.createHold`, provoking the race
  condition rather than serializing calls.
- Each thread seeds its own `SecurityContextHolder` (per-thread
  thread-local) before invoking the service.
- Waits for all threads to finish (30s timeout).

### Assertions

| # | Assertion                                                         | Observed |
|---|-------------------------------------------------------------------|----------|
| 1 | Exactly 1 thread returns a `HoldResponse` (success)               | ✅ 1     |
| 2 | Exactly 49 threads throw `DataIntegrityViolationException`        | ✅ 49    |
| 3 | Zero unexpected exception types                                   | ✅ 0     |
| 4 | Exactly 1 row with `status = 'ACTIVE'` for that seat_id in the DB | ✅ 1     |

### What this proves

- The partial unique index `one_active_hold_per_seat WHERE status =
  'ACTIVE'` is the sole coordination point. No application-level locks,
  synchronized blocks, distributed mutexes, or optimistic version
  columns are involved.
- Postgres correctly serializes concurrent INSERTs against the partial
  index and rejects duplicates atomically. All 49 losers see a clean
  constraint-violation exception (SQLSTATE 23505) that Spring
  translates into `DataIntegrityViolationException`, then
  `GlobalExceptionHandler` maps to HTTP 409 `SEAT_UNAVAILABLE`.
- The winner is non-deterministic (whichever thread's INSERT hits the
  index first), but the *outcome* — exactly one winner — is
  deterministic under any thread interleaving.

### Test runtime

- Full suite (including Spring context startup): ~27 seconds.
- Actual race + assertions after context load: ~1–3 seconds.
- Consistently green across repeated runs.

### Scope and honest limits

- **Single-instance app tested.** The design uses `FOR UPDATE SKIP
  LOCKED` in the expiry worker for future horizontal scaling, but the
  test does not spawn multiple app instances — only multiple threads
  inside one JVM.
- **50 threads chosen** because it comfortably exceeds the default
  HikariCP pool size (10), so some threads necessarily queue on
  connection acquisition. This still exercises the race — losers are
  distributed across time, but exactly one wins.
- **Same DB as dev**. Test cleans up its event/seat/holds in
  `@AfterEach`, but does not isolate to a dedicated schema or
  Testcontainer. Migration to Testcontainers is tracked as follow-up
  for CI portability, not a correctness concern for this validation.
- **No network-level race**. The test bypasses HTTP and calls the
  service directly, so JWT, deserialization, and filter-chain overhead
  are not part of the race window. The unique-index guarantee lives at
  the DB layer and is unaffected by upstream serialization.

### Related empirical evidence (manual Swagger runs)

- Two sequential POSTs on the same seat from different users → first
  returns 201, second returns 409 `SEAT_UNAVAILABLE`.
- Cancel a hold (status → `CANCELLED`) then POST again on the same
  seat → 201 succeeds immediately, confirming the partial index
  ignores non-ACTIVE rows.
- Expiry worker log line `Hold expiry worker: expired N hold(s)`
  observed to clear stale ACTIVE rows past their `expires_at`.