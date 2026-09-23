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
