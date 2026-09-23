# Phase 1 — Architecture & Data Model

**Status:** Complete
**Locked-in decisions:** ADR-001, ADR-002, ADR-003

## The senior-engineer questions this phase answers
- Monolith or modular?
- What are the aggregates?
- What's the transactional boundary?

---

## 1. Architecture — Modular Monolith

**Decision (ADR-001):** SeatLock is built as a **modular monolith** — one
deployable unit, but internally organized as independent modules that
communicate through well-defined interfaces (not by reaching into each
other's tables).

### Why not microservices
- Operational overhead (service discovery, distributed tracing, network
  failures between services) is not worth it for a single-team, single-DB
  project.
- Microservices push complexity to the network. A modular monolith keeps
  the same conceptual boundaries but keeps the network out of it.
- Any module can be extracted into its own service later if scale
  demands it — the interfaces are already in place.

### Modules (proposed)
- `catalog` — Events and Seats (read-heavy, mostly public)
- `booking` — Holds, Bookings, Cancellations (write-heavy, transactional)
- `payment` — Payment records, provider adapter, webhook handler
- `identity` — Users, tokens (Phase 5 — currently stubbed)
- `admin` — Admin-only event/seat management

Modules interact via **application services**, never by direct
repository access across module boundaries.

---

## 2. Domain Model — the entities

**Decision (ADR-002):** the core entities and their relationships.

### Entities

| Entity | Owned by module | Purpose |
|---|---|---|
| **User** | identity | The person booking tickets |
| **Event** | catalog | A movie/show at a venue on a date |
| **Seat** | catalog | One physical seat for one event |
| **Hold** | booking | Short-lived reservation before payment |
| **Booking** | booking | Permanent, paid record of ticket ownership |
| **Payment** | payment | Record of one payment attempt |
| **Cancellation** | booking | Sub-record of a cancelled booking |

### Relationships
```
User ──< Booking >── Event
              │
              └──< Seats (via booking_seats join)

Event ──< Seat
Event ──< Hold ──< Seats (via hold_seats join)
Booking ──< Payment
Booking ──< Cancellation (0 or 1)
```

### Aggregate roots
An aggregate root is the entity you reach through first; everything
inside its boundary is loaded and saved together.

- **Event** is an aggregate root. It owns its Seats and pricing tiers.
- **Booking** is an aggregate root. It owns its Cancellation and
  references its Payment.
- **Hold** is a short-lived aggregate root, owns its held seat references.

Cross-aggregate references are always by ID, never by object reference.

---

## 3. Key Invariants (rules the domain must never break)

- A seat is always in exactly one state: `available` / `held` / `booked`.
- A hold expires exactly 5 minutes after creation. Expired holds are
  swept back to `available`.
- A booking is immutable once confirmed. Only its `status` field
  transitions (`confirmed` → `cancelled`).
- Payment amount at booking time = sum of seat prices captured at hold
  creation time. Later price changes do not affect existing bookings.
- A seat belongs to exactly one event. Seats are not shared across events.
- A user can hold seats for multiple events simultaneously, but only one
  active hold per event at a time.

---

## 4. Transactional Boundaries

**Decision (ADR-003):** Checkout uses **two short database transactions
with a durable Payment record in between**. No external network call is
ever made inside a transaction.

```
TX1: verify hold → insert Payment(status='initiated')
[call payment provider — no DB locks held]
TX2: update Payment → 'succeeded' → mark seats 'booked' → create Booking
```

Recovery: a reconciliation job scans for stuck `Payment(status='initiated')`
rows and queries the provider for their true status.

---

## 5. What Phase 1 explicitly did NOT decide

These are handled in later phases — do not confuse them with Phase 1 concerns.

| Concern | Phase |
|---|---|
| Concrete DB schema (tables, columns, types) | Phase 3 |
| Locking mechanism (pessimistic vs optimistic) | Phase 3 |
| Isolation levels | Phase 3 |
| API URLs, request/response payloads | Phase 2 |
| Auth token internals | Phase 5 |
| Any framework code | Phase 3+ |

---

## Interview-ready lines

> **On architecture:** *"Modular monolith — one deployable, internally
> organized as modules communicating via interfaces. Gets the conceptual
> benefit of microservices without the operational cost."*

> **On aggregates:** *"Event, Booking, and Hold are aggregate roots.
> Cross-aggregate references are by ID, never by object reference — this
> keeps the aggregate loadable and savable as a unit."*

> **On transactional boundary:** *"Two short transactions with a durable
> Payment record between them, so the slow payment-provider call never
> holds a DB lock. Recovery is a reconciliation job on stuck
> Payment rows."*
