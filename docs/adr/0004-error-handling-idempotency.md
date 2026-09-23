# ADR-004: Error Handling & Idempotency Contract

**Status:** Accepted
**Date:** 2026-09-17

## Context
Every endpoint SeatLock exposes has two cross-cutting concerns that must
be settled once, not re-decided per endpoint:

1. **How does the server report failures to the client?** Without a
   uniform shape, the frontend ends up with a different error handler
   per screen, and a small backend change (renaming an error field)
   silently breaks unrelated features.
2. **How do we make retries safe?** Network blips, double-clicks, and
   flaky mobile connections are routine. A retried checkout that
   charges the card twice, or a retried hold that reserves seats twice,
   is a production incident. The client cannot always tell whether its
   first request reached the server — so retries have to be safe by
   construction, not by hope.

Both concerns cut across every write endpoint (holds, bookings,
cancellations, admin writes, webhooks). They deserve a single contract
that all endpoints implement uniformly.

## Decision
We adopt one error envelope for every failure response, and one
idempotency protocol for every unsafe operation.

### Error envelope
Every error response — from every endpoint, at every status code — uses
this exact shape:

```json
{
  "error": {
    "code": "SEATS_ALREADY_HELD",
    "message": "Seat A11 is no longer available.",
    "details": { "conflicting_seat_ids": [502] },
    "request_id": "req_a8f39c1b7d2e",
    "timestamp": "2026-09-17T14:22:00Z",
    "documentation_url": "https://docs.seatlock.example.com/errors/SEATS_ALREADY_HELD"
  }
}
```

Rules that make the envelope a contract:

- `code` is a stable, machine-readable string. Frontends branch on it.
  Once shipped, a code is **never renamed** — only new codes are added.
- `message` is human-readable and safe to display. It carries no
  contractual meaning; never parse it in client logic.
- `request_id` is required on every error, populated by middleware so no
  handler can forget it. Support looks up the exact log line by this id.
- `timestamp` is ISO 8601 UTC.
- `details` is endpoint-specific extra data; optional.

Validation errors use a dedicated sub-shape inside `details` so a form
with many bad fields can be reported in one round trip:

```json
"details": {
  "field_errors": [
    { "field": "seat_ids",    "code": "REQUIRED",    "message": "..." },
    { "field": "seat_ids[0]", "code": "NOT_INTEGER", "message": "..." }
  ]
}
```

The full error-code taxonomy (auth, input, resource, business rule,
system) lives in the API docs and is versioned by addition only.

### Idempotency protocol
Every unsafe endpoint (POST / PATCH / DELETE that changes state)
accepts an `Idempotency-Key` header — a client-generated UUID that
identifies one logical operation.

```
POST /bookings
Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000
```

The server implements a fixed algorithm:

```
1. Look up the key in the idempotency store.
2. If not found:
     process the request normally,
     store (key, request_hash, status_code, response) with a 24h TTL,
     return the response.
3. If found and the current request_hash matches the stored one:
     return the stored response verbatim (do NOT re-execute).
4. If found but the current request_hash differs:
     return 422 IDEMPOTENCY_KEY_REUSED.
```

Storage lives in Redis (chosen alongside Postgres in Phase 3). Keys
expire after 24 hours — long enough to survive any realistic retry
window without unbounded growth.

Which endpoints require an idempotency key is documented per-endpoint
in the API spec. In summary: every write that has real-world side
effects (a hold, a booking, a cancellation, an admin create, a
webhook delivery) requires one. GETs never do.

Retries are safe only for HTTP 408, 425, 429, and 5xx responses, and
for network errors where no response was received. Clients must use
exponential backoff with jitter and must include the same idempotency
key on every retry attempt.

## Alternatives Considered
- **Per-endpoint custom error shapes.** Rejected — leaks internal
  organization onto the wire, forces the frontend to write a bespoke
  error handler per screen, and makes cross-cutting concerns (i18n,
  logging, alerting) impossible to centralize.
- **HTTP status code as the only error signal, no envelope.** Rejected —
  status codes are too coarse. `409 Conflict` alone doesn't tell the
  client whether to refresh the seat map, redirect to seat selection,
  or show a "cancel window closed" message.
- **Server-generated idempotency keys returned to the client.** Rejected
  — the whole point of the key is that the *client* controls operation
  identity across retries. If the server generates the key, the client
  has to make one round trip just to get a key, defeating the purpose.
- **Longer key TTL (7 days).** Rejected — 24h covers every realistic
  retry window (network failures, background sync, user coming back to
  a stalled tab). Longer TTLs waste storage and delay noticing a client
  bug that reuses keys.
- **Idempotency for GETs.** Not needed. GETs are naturally idempotent
  because they don't change state; adding a key would be ceremony
  without benefit.

## Consequences
Positive:
- The frontend writes one error handler used across every endpoint.
  New endpoints inherit consistent error UX for free.
- Network-retry safety without duplicate side effects — the client can
  retry blindly on 5xx or network failure, knowing the server dedupes.
- Support triage is dramatically faster: a screenshot with `req_...`
  points to the exact log line in seconds.
- The error taxonomy doubles as a checklist during API design — "which
  of these codes could this endpoint return?" surfaces missing cases
  before code review.

Negative / Trade-offs:
- Every write endpoint must be wired to the idempotency store. Requires
  a shared middleware layer and disciplined use in controllers.
- Redis becomes a hard dependency for the write path. If Redis is down,
  writes must either fail closed (safer) or fall through without dedup
  protection (riskier) — a decision we revisit if it becomes real.
- Adds a small storage cost (roughly 1 KB per unsafe request for 24h).
- Error `code` values are contract-forever. Discipline is required to
  add new codes rather than rename existing ones.

Neutral:
- The envelope shape and idempotency algorithm are directly modeled on
  the conventions used by Stripe and other production payment APIs.
  Both transfer cleanly to real work and are defensible in interviews.
