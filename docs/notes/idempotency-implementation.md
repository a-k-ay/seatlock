# Idempotency — How It's Implemented in SeatLock

Design: ADR-004 + Phase 3 Summary.  
Code: `com.seatlock.holds.IdempotencyStore` + updated `HoldController`.

## Flow
- Header: `Idempotency-Key: <client-uuid>`
- Redis key: `idempotency:holds:<key>`
- Redis value: `<sha256-of-body>||<jsonResponse>`
- TTL: 24 hours
- Client sends header → server hashes body → lookup → HIT+match=return 
  cached, HIT+mismatch=422, MISS=process+store.

## What's stored where
- Redis: fast lookup + response cache (ephemeral, 24h).
- Postgres `payments.idempotency_key UNIQUE`: permanent audit trail 
  (added in Phase 3, populated in Phase 4/confirm-booking, not this slice).

## Rate-limiting note (deferred)
Idempotency prevents *duplicates*. It does NOT prevent *floods* from 
different keys. Rate limiting is a separate concern (Phase 5 follow-up).

Analogy: Like a receipt at a shop counter.

Customer hands over receipt number: "Here, I already ordered this."
Cashier checks ledger for that number.
Found + same order → hands over the same items (doesn't charge again).
Found + different order → "This receipt doesn't match. Fraud/mistake." (422)
Not found → new order, file the receipt.

Technical version, 4 lines:

Client sends Idempotency-Key: abc-123 header with the POST.
Server hashes the body, looks up abc-123 in Redis.
If Redis has it AND the stored hash matches → return the cached response (no DB write).
If not → process the request, store {hash, response} in Redis under abc-123 with 24h TTL.