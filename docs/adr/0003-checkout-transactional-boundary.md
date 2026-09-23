# ADR-003: Checkout Transactional Boundary

**Status:** Accepted
**Date:** 2026-09-01

## Context
Checkout is the critical path of SeatLock. It must satisfy three
guarantees simultaneously:

1. **No double-booking** — two users cannot both end up owning the same seat.
2. **No double-charging** — a user's retry (network blip, double-click)
   must not result in two payments.
3. **No lost bookings** — if the server crashes between charging the
   payment provider and recording the booking, we must recover cleanly.

The checkout flow has an unavoidable external call — to the payment
provider — which is slow (hundreds of milliseconds to seconds) and
unreliable. How we wrap this call in database transactions determines
whether the system holds up under load or collapses.

## Decision
We split checkout into **two short database transactions with a durable
Payment record in between**, and forbid any external network call from
happening inside a transaction.

```
TRANSACTION 1 (short, local):
  BEGIN
    verify seats are still held by this user
    insert Payment row (status = 'initiated', idempotency_key = <key>)
  COMMIT

[call payment provider — no DB locks held]

TRANSACTION 2 (short, local):
  BEGIN
    update Payment row → 'succeeded' (or 'failed')
    if succeeded: update seats → 'booked',
                  insert Booking row,
                  mark Hold → 'converted'
  COMMIT
```

Seat rows are locked with `SELECT ... FOR UPDATE` inside each transaction
to serialize concurrent access. The `idempotency_key` column has a
`UNIQUE` constraint, so a retried request fails Transaction 1 immediately
on duplicate-key error — no second payment is ever started.

If the process crashes after the provider call but before Transaction 2,
a reconciliation job scans for `Payment` rows stuck in `initiated`,
queries the provider for their true status, and finishes the work. This
is a lightweight application of the outbox / reconciliation pattern.

## Alternatives Considered
- **Single transaction wrapping the whole flow (external call included):**
  simplest to write, catastrophic in production. The transaction holds
  row locks for the full duration of the network call, exhausting the
  database connection pool under load and freezing the entire service
  when the provider is slow. Rejected — this is the anti-pattern the
  rule "never call external services inside a transaction" exists to
  prevent.
- **No transactions, just sequential calls:** removes the connection-pool
  risk but loses atomicity. A crash between "charged card" and "created
  booking" leaves the user paid but ticketless, with no reliable way to
  reconcile. Rejected.
- **Two-transaction split with durable Payment record (chosen):** short
  transactions preserve throughput; the `initiated` Payment row is the
  durable checkpoint that makes crash recovery possible; the unique
  idempotency key gives duplicate-charge protection at the DB level.

## Consequences
Positive:
- Transactions are milliseconds long — connection pool stays healthy
  under load.
- Payment provider slowness affects only the users currently checking
  out, not the whole service.
- Crash recovery is straightforward: one reconciliation query on
  `Payment WHERE status = 'initiated'`.
- Duplicate requests fail fast and deterministically at the DB layer.

Negative / Trade-offs:
- Two transactions means two places where a bug can leave state
  inconsistent; requires careful testing (Phase 7).
- Requires a reconciliation job (Phase 4) — one more moving part to
  operate and monitor.
- Client must generate and send an idempotency key on every checkout
  request; this is a contract we document in the API (Phase 2).

Neutral:
- This is the same pattern used by production payment systems (Stripe,
  Razorpay integrations, etc.). It's defensible in any interview and
  transfers directly to real-world work.
