# API Design & HTTP Reference

## HTTP Status Codes (What Actually Matters)

### Success family (2xx)
- **200 OK** — request succeeded, response has body
- **201 Created** — new resource created, include `Location` header
- **202 Accepted** — received but result is uncertain/pending; client should POLL a status endpoint, NOT retry
- **204 No Content** — success but no response body (e.g., DELETE)

### Client error (4xx) — client did something wrong, retry won't help
- **400 Bad Request** — malformed input (syntax)
- **401 Unauthorized** — not authenticated
- **403 Forbidden** — authenticated but not allowed
- **404 Not Found** — resource doesn't exist
- **409 Conflict** — server state conflicts with request (e.g., seat already held, duplicate email)
- **410 Gone** — resource used to exist, now permanently gone
- **422 Unprocessable Entity** — semantically wrong request (e.g., idempotency key reused with different payload) — CLIENT BUG

### Server error (5xx) — safe to retry
- **500 Internal Server Error** — unexpected server error
- **502 Bad Gateway** — upstream service failed
- **503 Service Unavailable** — server temporarily unavailable
- **504 Gateway Timeout** — upstream service timed out

### Special
- **408 Request Timeout** — server timed out waiting for request
- **429 Too Many Requests** — rate limited; honor `Retry-After` header

## 409 vs 422 (Frequently Confused)

- **409** = server state conflict, user might fix by changing input
- **422** = client sent semantically broken request, client bug

## Retry Semantics

| Status | Retry? |
|---|---|
| 200-299 (except 202) | No — success |
| 202 | No — POLL instead |
| 400, 401, 403, 404, 405, 409, 410, 422 | No — terminal, retry won't help |
| 408, 425, 429 | Yes with backoff |
| 500, 502, 503, 504 | Yes with backoff |
| Network error | Yes with backoff |

**Backoff pattern:** 1s, 2s, 4s, 8s, then give up.

## Idempotency

**Idempotency = same request sent twice = same result**, no side effects on retry.

### Idempotency Key Rules
- Client sends `Idempotency-Key: <unique-string>` header on mutating requests (POST/PUT/DELETE).
- Server stores `key → response` in a store (Redis + DB backup).
- On retry with same key + same body → return stored response.
- On retry with same key + DIFFERENT body → **422 IDEMPOTENCY_KEY_REUSED** (client bug).

### The Idempotency Chain
For payment flows: `Client → Your Service → Gateway`. **Every layer must honor the same key.** If any layer breaks the chain, you double-charge.

## Money in APIs
- Send/store as **integer in the smallest currency unit** (paise, cents). No decimals.
- Always pair with **currency code** (`INR`, `USD`).
- ₹1,250.75 = `{"amount": 125075, "currency": "INR"}` — no dot, no float.