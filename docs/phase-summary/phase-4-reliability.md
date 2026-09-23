# Phase 4 — Reliability

**Status:** Complete  
**Dates:** 2026-09-22  
**Related ADRs:** ADR-006 (Concurrency), ADR-007 (Confirm-Booking Flow)

## Purpose

Phase 4 turns the schema and concurrency mechanisms from Phase 3 into 
a system that behaves correctly under failure. It solves the top-3 
hard problems from Phase 0 end-to-end:

- **Double-booking:** solved at the database layer via ADR-006's 
  partial unique index.
- **Abandoned-hold cleanup:** solved by the expiry worker below.
- **Paid but crashed:** solved by the confirm-booking flow (ADR-007) 
  + reconciliation job.

## Hold-Expiry Worker

A background job that flips ACTIVE holds past their `expires_at` to 
EXPIRED.

**Cadence:** every 30 seconds.

**Query:**

BEGIN;
SELECT id FROM holds
WHERE status = 'ACTIVE'
AND expires_at < NOW()
FOR UPDATE SKIP LOCKED
LIMIT 100;

UPDATE holds SET status = 'EXPIRED', updated_at = NOW()
WHERE id IN (<batch>);

COMMIT;



`SKIP LOCKED` allows multiple worker instances to run without 
fighting each other for the same rows — each worker grabs a fresh 
batch. The partial index `holds_expires_at_idx WHERE status='ACTIVE'` 
keeps this query fast forever.

**Lazy inline check:** if the worker is offline, an inline check 
during hold creation (`expires_at < NOW() AND status = 'ACTIVE'` → 
treat as expired) covers the gap.

## Confirm-Booking Flow (Recap of ADR-007)

Ordered flow for `POST /bookings/confirm`:

1. Redis idempotency check (cache path).
2. Transaction begins (READ COMMITTED).
3. `SELECT FOR UPDATE` the hold row — validate ownership, status, 
   expiry.
4. Gateway call with **the same idempotency key**.
5. On success: booking + hold status transition + payment row + 
   COMMIT + Redis cache set.
6. On failure: payment row (FAILED) + COMMIT.
7. On timeout: payment row (PENDING) + COMMIT + return 202. Client 
   polls the payment status endpoint.

## Failure-Mode Matrix

| Failure | System behavior |
|---|---|
| Client sends same idempotency key twice, same body | Cached response returned from Redis; no side effects |
| Client sends same key with different body | 422 IDEMPOTENCY_KEY_REUSED; loud failure |
| Redis down at request time | Falls back to Postgres UNIQUE lookup on idempotency_key |
| Two concurrent confirm requests for same hold | FOR UPDATE lock serializes; loser sees CONFIRMED hold and returns cached booking |
| Gateway returns FAILED | Payment row saved with FAILED; hold stays ACTIVE for retry with different card |
| Gateway returns TIMEOUT | Payment row saved with PENDING; return 202; reconciliation resolves later |
| Service crashes before COMMIT | No booking, no payment row. Gateway may have charged (if it succeeded on their side). Reconciliation catches the orphan and refunds |
| Service crashes AFTER COMMIT, before Redis SET | Redis is cold; next retry falls back to Postgres UNIQUE, finds the payment row, returns 200 with existing booking |
| Client retries an idempotent-safe status (408, 429, 5xx) | Backoff + retry, idempotency key protects against duplicate side effects |
| Client retries a terminal status (4xx) | Client bug; do not retry |

## Reconciliation Strategy

Nightly job at 02:00 AM local:

1. Fetch payment gateway transactions for the last 24 hours.
2. For each gateway transaction:
   - Match against `payments.gateway_reference`.
   - Match exists → verify amount and status, alert on mismatch.
   - No match → **ORPHAN** (gateway charged, we have no record). 
     Alert on-call. Investigate + refund workflow.
3. For each `payments` row with status = 'PENDING' older than 1 hour:
   - Query gateway by our `idempotency_key`.
   - Resolve to SUCCESS or FAILED based on gateway's authoritative 
     answer.

Reconciliation is out-of-scope for the demo implementation but the 
schema, gateway_reference storage, and PENDING state make it 
straightforward to add.

## Retry Contract

| HTTP Status | Client retry? | Notes |
|---|---|---|
| 200-299 (except 202) | No | Done |
| 202 Accepted | No — poll instead | GET the status endpoint |
| 400, 401, 403, 404, 405, 409, 410, 422 | No | Client error, terminal |
| 408 Request Timeout | Yes with exp backoff | |
| 425 Too Early | Yes with exp backoff | |
| 429 Too Many Requests | Yes, honor Retry-After | |
| 500, 502, 503, 504 | Yes with exp backoff | Transient server error |
| Network error / connection reset | Yes with exp backoff | Request may not have arrived |

Backoff: 1s, 2s, 4s, 8s, then give up.

## What's Next — Phase 5: Security

- Auth flow: `POST /auth/login`, JWT issuance, JWT filter on 
  protected endpoints.
- Rate limiting to protect against retry storms and abuse.
- Deferred concept: JWT internals (HS256 vs RS256, claims, 
  expiration).

## Open Items

- Reconciliation job is designed but not implemented; a stub for 
  Phase 8 delivery.
- `PENDING` payment TTL is 1h in the spec; may need tuning.
- Metric/log naming for these flows is deferred to Phase 6 
  (Observability).