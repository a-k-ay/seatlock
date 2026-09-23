# Phase 2 — API Design

**Status:** Complete
**Locked-in decisions:** ADR-004 (error + idempotency contract), URL versioning (see below)

## The senior-engineer questions this phase answers
- REST vs RPC vs GraphQL?
- Idempotency keys?
- Error contract?
- Versioning?

---

## 1. Style — REST

**Decision:** SeatLock exposes a **REST API over HTTP/JSON**.

### Why REST
- Ticketing is fundamentally CRUD-shaped (events, seats, bookings) — a
  natural fit for resource-oriented URLs.
- Wide industry familiarity — every backend and every frontend engineer
  reads REST fluently, no framework-specific tooling required.
- Standard HTTP caching, status codes, and middleware "just work".
- Standard question in interviews; strong ability to defend it.

### Why not RPC / GraphQL
- **RPC (e.g., gRPC):** great for internal service-to-service traffic;
  overkill for a public-ish API and requires generated clients.
- **GraphQL:** shines when clients need to compose arbitrary field
  selections across many entities. SeatLock's screens map cleanly to a
  handful of purpose-built endpoints, so GraphQL's flexibility would
  cost complexity without benefit.

---

## 2. The 12 Endpoints (SeatLock v1)

Full spec lives in the API tab. Summary table:

| # | Endpoint | Purpose | Auth |
|---|---|---|---|
| 1 | `GET /events` | Browse events (list) | Public |
| 2 | `GET /events/{id}` | Event details | Public |
| 3 | `GET /events/{id}/seats` | Seat map | Public |
| 4 | `POST /events/{id}/holds` | Temporarily reserve seats | User |
| 5 | `POST /bookings` | Convert hold → paid booking | User |
| 6 | `GET /bookings` | My booking history (list) | User |
| 7 | `GET /bookings/{id}` | Booking detail (with QR) | User |
| 8 | `POST /bookings/{id}/cancellation` | Cancel a booking | User |
| 9 | `POST /webhooks/payments` | Payment provider callback | HMAC signature |
| 10 | `POST /admin/events` | Create event | Admin |
| 11 | `PATCH /admin/events/{id}` | Update event | Admin |
| 12 | `POST /admin/events/{id}/seats` | Create seat map | Admin |

---

## 3. Reusable Schemas — 4 building blocks

- **`Money`** — `{ amount (integer, smallest unit), currency }`
- **`SeatSummary`** — seat with row/label/price
- **`EventSummary`** — event with venue/showtime (embedded form)
- **`ErrorEnvelope`** — every error, everywhere (see ADR-004)

Any full response is assembled from these building blocks + top-level
IDs. Design endpoints as compositions, not from scratch.

---

## 4. Error Contract (ADR-004)

Every error uses one shape:

```json
{
  "error": {
    "code": "SEATS_ALREADY_HELD",
    "message": "Seat A11 is no longer available.",
    "details": { "conflicting_seat_ids": [502] },
    "request_id": "req_a8f39c1b7d2e",
    "timestamp": "2026-09-17T14:22:00Z"
  }
}
```

- `code` is stable forever (never renamed, only added).
- `request_id` is required — populated by middleware, used by support.
- Validation errors use a `field_errors` sub-shape inside `details`.
- Codes grouped by category: auth, input, resource, business rule,
  system. Full taxonomy in ADR-004.

---

## 5. Idempotency Contract (ADR-004)

Every write endpoint (POST/PATCH/DELETE that changes state) accepts an
`Idempotency-Key` header. Server stores `(key, request_hash, response)`
in Redis for 24 hours.

- **Same key + same payload** → returns stored response (no re-execute)
- **Same key + different payload** → 422 `IDEMPOTENCY_KEY_REUSED`
- **New key** → executes normally, stores response

Retries safe only for 408, 425, 429, 5xx, and network errors — with
exponential backoff + jitter + the same idempotency key.

---

## 6. Versioning

**Decision:** **URI versioning** — every endpoint is prefixed with `/api/v1/`.

Full paths: `/api/v1/events`, `/api/v1/bookings`, `/api/v1/admin/events`, etc.

### The four approaches — and why URI wins

| Approach | Example | Trade-offs |
|---|---|---|
| **URI versioning** | `/api/v1/events` | Simple, visible in logs/browser, cache-friendly, easy to reason about. Slight REST-purist objection (URL should identify a resource, not a version). |
| Header versioning | `Accept-Version: v1` | Cleaner URLs. Invisible in browser/logs; harder to test with curl; caching layers must vary on the header. |
| Content negotiation | `Accept: application/vnd.seatlock.v1+json` | Most "correct" per HTTP spec. Awkward to write, obscure to junior devs, poor tooling. |
| No versioning | `/api/events` | Only viable if you never make breaking changes. In practice, you always will. |

**Reason for URI:** legibility and operational simplicity beat REST
purity. When something breaks in production, seeing `v1` right in the
URL tells the on-call engineer everything they need. Cache keys are
naturally distinct. Every dev on the team understands it on day one.

### Versioning rules

**A `v1` → `v2` bump is required only for BREAKING changes.** Everything
else stays in `v1`.

**Breaking (requires new version):**
- Removing a field from a response
- Renaming a field
- Changing a field's type (`int` → `string`)
- Changing an endpoint's URL or HTTP method
- Changing semantics (a status now means something different)
- Making an optional request field required

**Additive (stays in current version):**
- Adding a new endpoint
- Adding a new field to a response
- Adding a new optional field to a request
- Adding a new value to an enum (as long as clients follow "ignore unknown values")
- Adding a new error `code` (frontends must have a default case)

**Deprecation policy (when a `v2` eventually exists):**
- `v1` and `v2` run in parallel for a **minimum deprecation window** (typically 6 months).
- `v1` responses include a `Deprecation` and `Sunset` HTTP header once `v2` ships.
- After the window, `v1` returns `410 Gone` with a `documentation_url` pointing to the migration guide.

### What SeatLock v1 will ship with
All endpoints under `/api/v1/`. No `v2` planned for the portfolio scope.

---

## 7. Content & Transport Conventions

- **Content-Type:** `application/json` for both request and response bodies.
- **Character encoding:** UTF-8 always.
- **Timestamps:** ISO 8601 UTC (e.g., `2026-09-17T14:22:00Z`) — server is the clock.
- **Money:** integer in smallest currency unit, always paired with currency code.
- **IDs in URL, filters in query string:** `/bookings/9001` vs `/bookings?status=confirmed`.
- **Naming:** `snake_case` for JSON keys throughout.
- **HTTPS required** in all environments except local dev.

---

## 8. What Phase 2 explicitly did NOT decide

| Concern | Phase |
|---|---|
| Database schema (tables, columns, indexes) | Phase 3 |
| Locking mechanism for seat holds | Phase 3 |
| Isolation levels | Phase 3 |
| Framework code (Spring Boot controllers, DTOs) | Phase 3+ |
| JWT internals, refresh tokens | Phase 5 |
| Rate limiting rules | Phase 5 |
| Log format, metric names | Phase 6 |

---

## Interview-ready lines

> **On REST vs alternatives:** *"REST because the domain is CRUD-shaped
> and the client set is small and known. GraphQL earns its complexity
> only when clients need to compose arbitrary field selections, which we
> don't."*

> **On versioning:** *"URI versioning under `/api/v1/`. It's the least
> clever option — visible in logs, cache-friendly, understood by every
> engineer on the team. I bump only for breaking changes; additive
> changes stay in `v1` because clients that ignore unknown fields
> aren't broken by them."*

> **On the error contract:** *"One envelope shape for every error —
> stable machine-readable `code`, human-readable `message`,
> `request_id` for support. Validation errors nest a `field_errors`
> list so multi-field forms surface every failure in one round trip."*

> **On idempotency:** *"Client generates an `Idempotency-Key` per
> operation; server dedupes retries by storing `(key, request_hash,
> response)` in Redis for 24 hours. Same key with a different payload
> returns 422 — that's a client bug we surface rather than silently
> paper over."*
