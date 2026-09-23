# ADR-007: Confirm-Booking Flow

**Status:** Accepted  
**Date:** 2026-09-22

## Context

The confirm-booking flow is where the "paid but crashed" problem 
(Phase 0, top-3 hard problems) gets solved. This one endpoint must:

- Be idempotent under client retries.
- Prevent double-charging under any failure mode (network, service 
  crash, DB failure, gateway timeout).
- Consume an ACTIVE hold atomically with creating a booking and 
  writing a payment record.
- Never leave the system in a state where the gateway has money but 
  SeatLock has no booking (or has a booking but no payment).
- Provide a clear contract for how clients recover from uncertain 
  outcomes.

## Decision

**Order of operations for POST /bookings/confirm:**

1. Redis idempotency check first (fast path for retries).
2. `BEGIN TRANSACTION` (READ COMMITTED).
3. `SELECT * FROM holds WHERE id = ? FOR UPDATE` — pessimistic lock. 
   Validate ownership, ACTIVE status, and non-expired.
4. Call payment gateway **with the same idempotency key passed 
   downstream**. Gateway is responsible for its own dedup.
5. On gateway SUCCESS: INSERT booking, UPDATE hold to CONFIRMED, 
   INSERT payment with status='SUCCESS'. COMMIT.
6. On gateway FAILED: INSERT payment with status='FAILED' (for 
   audit). COMMIT. Hold stays ACTIVE, user may retry with different 
   card.
7. On gateway TIMEOUT (uncertain): INSERT payment with 
   status='PENDING'. COMMIT. Return 202 with payment id. Client 
   polls `GET /payments/{id}` until resolved.
8. After COMMIT, SET Redis cache with response for future retries.

**Reconciliation:** a nightly job fetches gateway transactions for 
the past 24h, matches them against the payments table by 
gateway_reference, and alerts on any mismatch (orphaned charges or 
ghost payments). PENDING payments are resolved to SUCCESS/FAILED 
based on the reconciled state.

**Retry contract:** clients retry only on 408/425/429/5xx and network 
errors. 4xx errors are terminal. 202 responses trigger polling, not 
retry.

## Alternatives Considered

**Call gateway BEFORE opening the transaction**  
Rejected. If the app crashes between gateway success and transaction 
begin, we have money at the gateway with no locked hold; a competing 
request could then consume the hold. Ordering matters: the hold is 
locked first, gateway is called second, all within one transaction.

**Skip the gateway idempotency key**  
Rejected. Without downstream idempotency, a retry after a crash 
becomes a double-charge. The idempotency chain (client → us → 
gateway) is non-negotiable.

**Use Redis alone for idempotency, drop the payments unique index**  
Rejected. Redis TTL expires after 24h. Retries after that would 
double-charge without the Postgres UNIQUE constraint as the permanent 
backstop.

**Two-phase commit / distributed transactions across DB and gateway**  
Rejected. Most payment gateways do not participate in 2PC. Adds 
complexity for a marginal correctness gain. Reconciliation catches 
what 2PC would.

**Return the response before COMMIT (write-behind)**  
Rejected. Client would receive a success response for a transaction 
that could still fail. Correctness > speed here.

**Synchronous reconciliation inside the request path**  
Rejected. Reconciliation is a background concern. Doing it in the 
hot path adds unbounded latency.

## Consequences

### Positive

- End-to-end idempotency: client → SeatLock → gateway all share the 
  same key.
- No lost bookings from crashes: reconciliation catches any 
  desynchronization.
- No double-charges: Redis + Postgres UNIQUE + gateway dedup form a 
  three-layer defense.
- Uncertain outcomes are surfaced cleanly (202 + poll pattern) 
  rather than hidden.
- Full audit trail — every payment attempt (success, failure, 
  pending) is a row in the payments table.

### Negative

- Gateway call happens **inside** the DB transaction, so the hold 
  row lock is held for the gateway's response time. Requires a 
  strict gateway timeout (e.g. 5s). Under gateway slowness, request 
  throughput drops.
- Depends on gateway supporting idempotency keys. Choice of gateway 
  is now a constraint.
- Requires a reconciliation job and paging for its failures. Adds 
  operational burden.
- 202 semantics push complexity to clients — they must implement 
  polling logic. Documented explicitly in the API contract.
- PENDING payments must have a max age before being force-resolved 
  or flagged. Adds a state to reason about.

### Neutral

- The hold row's `SELECT FOR UPDATE` blocks the expiry worker for 
  the duration of the confirm transaction. Acceptable: the confirm 
  is short, the worker retries the row on the next sweep.
- Reconciliation is nightly. In real production this might be 
  hourly. Not relevant at portfolio scale.