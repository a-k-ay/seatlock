# ADR-001: Adopt a Modular Monolith Architecture

**Status:** Accepted
**Date:** 2026-09-01

## Context
SeatLock is a backend service for concurrent event seat booking, built solo
over ~6–8 weeks at ~10 hrs/week. The system has clear internal domains
(events, seats, holds, bookings, payments) that could plausibly be separate
services, but there is no team-scaling pressure, no independent-deploy
requirement, and no traffic profile that demands per-domain scaling today.

The core hard problems (double-booking race, hold expiry, payment idempotency)
are all solvable — and in fact easier — inside a single transactional
boundary. Network hops between services would add failure modes without
adding value at this stage.

## Decision
We adopt a **modular monolith**: one deployable Spring Boot application,
one PostgreSQL database, with the codebase split into clearly bounded
modules (`events`, `seats`, `holds`, `bookings`, `payments`, `common`).
Modules expose narrow public interfaces; cross-module access to internal
classes is forbidden and enforced by package structure and code review.

## Alternatives Considered
- **Pure monolith (no module boundaries):** simplest, but risks the codebase
  becoming a tangled ball as features grow. Rejected because the discipline
  of module boundaries is cheap to add now and expensive to retrofit later.
- **Microservices from day one:** operationally expensive — separate
  deployments, network failures on every internal call, distributed
  transactions (Saga pattern) instead of a single ACID transaction, and
  per-service observability setup. The benefits (independent scaling, team
  autonomy) do not apply to a solo project with uniform load. Rejected as
  premature.
- **Modular monolith (chosen):** matches current constraints while keeping
  future extraction possible with minimal refactoring.

## Consequences
Positive:
- Single transactional boundary makes the double-booking and payment
  problems tractable with plain database transactions.
- One deployable, one database — dramatically simpler CI, local dev, and
  observability setup.
- Module boundaries preserve the option to extract a service later if a
  real scaling reason emerges.

Negative / Trade-offs:
- Boundaries are enforced by convention and code review, not by network.
  Discipline is on the author.
- Cannot scale one module independently of another; the whole app scales
  together.
- If the payment module ever needs its own release cadence, compliance
  isolation, or its own team, extraction work will be required.

Neutral:
- Aligns with the "Monolith First" approach (Fowler); a well-known and
  defensible position at every level of interview.
