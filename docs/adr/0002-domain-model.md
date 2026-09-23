# ADR-002: Domain Model — Five Core Entities

**Status:** Accepted
**Date:** 2026-09-01

## Context
SeatLock's core purpose is to guarantee "one seat, one buyer" under load,
with time-boxed holds and idempotent payments. To support these guarantees
and give each concept a clean lifecycle, we need to decide what the
persistent entities are, what belongs on each, and how they relate.

The alternative of collapsing concepts (e.g. representing seats as a JSON
blob on an event, or making a booking a `status = pending → confirmed`
row without a separate hold) simplifies the schema at the cost of making
concurrency, expiry, and auditing significantly harder.

## Decision
We model the domain with **five core entities**:

1. **Event** — a bookable show (venue, time, on-sale status).
2. **Seat** — a single physical seat belonging to one event, stored as
   its own row so it can be individually locked. Status: `available`,
   `held`, or `booked`.
3. **Hold** — a temporary claim on one or more seats by a user, with an
   `expires_at`. Lifecycle: `active`, `expired`, `converted`, `released`.
4. **Booking** — the permanent record of a successful purchase, with a
   human-readable reference and total amount. Immutable once created
   (except a `cancelled` transition).
5. **Payment** — one attempt (successful, failed, or refunded) to charge
   for a booking. Carries the `idempotency_key` with a unique constraint.
   A booking may have many payment rows.

**User is external** for now: represented only by a `user_id` on Hold and
Booking, resolved from the auth token. A User entity will be introduced
in Phase 5 (Security) if needed.

## Alternatives Considered
- **Seats as a JSON blob on Event:** simpler schema, but two users booking
  different seats in the same event would contend on the same Event row.
  Rejected — kills row-level concurrency, which is the whole point.
- **Collapse Hold into Booking with status column:** simpler on paper, but
  mixes two lifecycles (expiring vs permanent), muddles queries (every
  query needs a status filter), and confuses business meaning (holds are
  not sales). Rejected.
- **Collapse Payment into Booking (single `payment_status` column):**
  loses payment history (retries, refunds) and has no natural home for
  the idempotency key. Rejected — real payment systems always separate
  these.
- **Five-entity model (chosen):** each entity has one clear lifecycle,
  one clear purpose, and clean query paths.

## Consequences
Positive:
- Individual seat rows enable `SELECT ... FOR UPDATE` at seat granularity,
  which is the foundation of the "one seat, one buyer" guarantee.
- Hold's `expires_at` gives the abandoned-cart cleanup a trivial query:
  `WHERE status = 'active' AND expires_at < now()`.
- Payment's unique `idempotency_key` gives us duplicate-charge protection
  at the database level, not just in application code.
- Booking is immutable and audit-friendly — regulators and finance teams
  can trust it.

Negative / Trade-offs:
- More tables and joins than a collapsed model.
- Requires a background job for hold expiry (introduced in Phase 4).
- Seat status is duplicated between the Seat row and the Hold/Booking
  linkage; keeping them consistent requires transactional discipline.

Neutral:
- Deferring the User entity keeps Phase 1 focused on domain concurrency,
  not auth. The trade-off is explicit and documented here.
