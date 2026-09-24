# Database Concurrency, Isolation, and Money

## The Three Concurrency Approaches

When two requests hit the same row at the same time, you have three ways to prevent corruption.

### Option A: Pessimistic Locking (`SELECT ... FOR UPDATE`)
- Lock the row before touching it. Others wait.
- Good for: multi-row transactions, confirm-booking flow.
- Bad for: high contention (throughput collapses).

### Option B: Optimistic Locking (version column)
- Read with version, save with `WHERE version = ?`. On mismatch, retry.
- Good for: read-heavy workloads, rare conflicts.
- Bad for: hot rows under contention (retry storms).

### Option C: Partial Unique Index (database constraint)
- `CREATE UNIQUE INDEX name ON table(col) WHERE status = 'ACTIVE';`
- DB refuses duplicate INSERTs. Application catches SQL error 23505.
- Good for: single-row invariants under high contention.
- Bad for: complex multi-row invariants.

**Best pattern for booking-style systems:** C for create-race + A for confirm-flow.

## Transaction Isolation Levels

| Level | Behavior | Use for |
|---|---|---|
| READ COMMITTED | Each statement sees latest committed. Postgres default. | 90% of business flows |
| REPEATABLE READ | Transaction sees frozen snapshot from start | Reports, reconciliation |
| SERIALIZABLE | DB pretends transactions ran one-at-a-time | Only if nothing else works |

**Mnemonic:**  
- READ COMMITTED = "I re-read and see whatever's committed **right now**."  
- REPEATABLE READ = "My reads are **repeatable** — same query, same answer, all through my transaction."

## Money Handling (Never Get This Wrong)

**Store as `BIGINT` in the smallest currency unit.** Never `DECIMAL`, never `FLOAT`.

- Why: IEEE 754 floats can't represent 0.1 + 0.2 exactly (= 0.30000000000000004).
- ₹1,250.75 → `125075` (paise) in DB.
- Always pair with `currency VARCHAR(3)`.

## Append-Only Pattern (History Preservation)

For entities with lifecycle (holds, bookings, payments):
- Never DELETE rows. Add a `status` column.
- Transition through statuses (`ACTIVE` → `EXPIRED` or `CONFIRMED`).
- Combine with partial unique index scoped to `status = 'ACTIVE'` — expired rows drop out of the constraint automatically.

Benefit: full audit trail, cheap forensics, and reusing the "same seat" is just an INSERT.