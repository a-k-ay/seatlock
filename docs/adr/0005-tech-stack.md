# ADR-005: Technology Stack

**Status:** Accepted  
**Date:** 2026-09-21

## Context

SeatLock is a backend service that manages concurrent seat booking with 
one-seat-one-buyer guarantees, time-boxed holds, idempotent payments, 
and full observability. It is a modular monolith (per ADR-001) backed by 
a single relational database.

The stack must support:

- Strong transactional guarantees and row-level locking (for the 
  double-booking prevention story).
- Fast, expiry-aware key-value storage (for the idempotency-key store).
- Reliable schema evolution (this is a portfolio project that will be 
  iterated on across phases; migrations must be versioned).
- Realistic integration tests — concurrency behavior cannot be validated 
  against in-memory fakes.
- Enough scale headroom to plausibly demo the concurrency story 
  (thousands of concurrent hold requests).
- Reasonable interview credibility: choices should be defensible in a 
  technical discussion and reflect current industry practice for Java 
  backends.

The developer is solo, working ~10 hrs/week, with prior BA background 
and growing backend depth. Time spent on plumbing must be minimized so 
the interesting concurrency and reliability work gets the airtime it 
deserves.

## Decision

The SeatLock service will be built on the following stack:

**Language & Runtime**
- **Java 21** (LTS)

**Web Framework**
- **Spring Boot 3.x**

**Build Tool**
- **Maven**

**Primary Database**
- **PostgreSQL 16**

**Auxiliary Datastore**
- **Redis** — used exclusively as the idempotency-key store with TTLs.

**Database Migrations**
- **Flyway** — versioned SQL migrations, applied on application startup.

**Integration Testing**
- **Testcontainers** — real PostgreSQL and Redis containers spun up for 
  integration tests, torn down at the end of each run.

**Boilerplate Reduction**
- **Lombok** — used on JPA entities to eliminate getter/setter/equals/
  hashCode boilerplate. Records used for DTOs.

**Authentication**
- **Spring Security + self-issued JWT**, scoped minimally: one 
  `POST /auth/login` endpoint with a small set of seeded users, a JWT 
  filter validating tokens on protected endpoints. No user registration, 
  password reset, refresh tokens, or role-based permissions beyond 
  authenticated/unauthenticated.

## Alternatives Considered

**Java 17 vs Java 21**  
Java 17 is universally supported and the safe default. Java 21 was chosen 
because virtual threads directly address SeatLock's I/O-bound concurrency 
profile: many concurrent hold requests, each briefly blocked on database 
calls. Virtual threads let a modest server handle far more concurrent 
requests without expanding the OS thread pool. This is a defensible, 
project-relevant reason for choosing 21 over 17.

**Gradle vs Maven**  
Gradle has faster incremental builds and a more concise DSL. Maven was 
chosen because it remains the dominant build tool across enterprise Java 
shops and MNC codebases, which aligns with the interview target audience. 
The verbosity is acceptable for a project of this size.

**MySQL vs PostgreSQL**  
Both support row-level locking. PostgreSQL was chosen for its stronger 
partial-index support, cleaner `SELECT ... FOR UPDATE SKIP LOCKED` 
semantics (relevant to hold-cleanup workers in Phase 4), and better JSON 
column support.

**Redis vs pure-Postgres idempotency store**  
PostgreSQL can implement an idempotency store with a `TEXT PRIMARY KEY` 
table and a scheduled cleanup job. Redis was chosen because native TTLs, 
sub-millisecond reads, and atomic `SETNX`-style operations model the 
idempotency use case more directly. Postgres remains the source of truth 
for all business state; Redis holds only ephemeral idempotency records.

**Lombok vs no Lombok**  
Java 21 records handle immutable DTOs cleanly without Lombok. However, 
JPA entities require mutable state and no-arg constructors, which records 
cannot express. Rather than write ~30 lines of boilerplate per entity, 
Lombok is used for entities. Records remain the choice for request and 
response DTOs.

**Auth alternatives**  
- Basic Auth: simplest but not credible for a portfolio project.
- OAuth2 Resource Server (external issuer like Keycloak/Auth0): more 
  production-realistic but adds an external moving part that distracts 
  from the concurrency story. Overkill for scope.
- Skipping auth entirely: leaves every protected endpoint accepting a 
  fake `userId` in the request body, which is not credible.

JWT self-issued with a minimal flow strikes the balance between 
credibility and scope discipline.

**Skip Flyway, manage schema manually**  
Rejected. Ad-hoc schema management makes environment parity impossible 
and is not defensible in interviews. Flyway is the industry default.

**Skip Testcontainers, use H2 in-memory database**  
Rejected. H2 does not faithfully implement PostgreSQL's row-locking 
behavior. Concurrency tests against H2 would give false confidence — 
the exact opposite of what SeatLock is trying to demonstrate.

## Consequences

### Positive

- Java 21 virtual threads enable a plausible concurrency story on a 
  single modest server.
- Maven aligns with the dominant enterprise Java tooling, matching the 
  interview target audience.
- PostgreSQL 16 gives the transactional and locking primitives the 
  concurrency story depends on.
- Redis makes the idempotency store implementation clean and expressive, 
  with TTLs handled natively.
- Flyway ensures schema changes are versioned and reproducible across 
  environments.
- Testcontainers means integration tests run against the real database 
  engines — critical for validating the double-booking prevention 
  guarantees claimed in Phase 3.
- Lombok reduces entity boilerplate, keeping code reviews focused on 
  business logic rather than accessor noise.
- Minimal JWT auth is credible in interviews without consuming project 
  time better spent on the core reliability story.

### Negative

- Java 21 is newer and some team members in an enterprise setting may 
  not have used virtual threads; this is not a concern for a solo 
  portfolio project but would matter in a team context.
- Maven builds are slower than Gradle for large projects. Not a concern 
  at SeatLock's scale.
- Two datastores (PostgreSQL + Redis) means two things to run locally 
  and two things to reason about in incident scenarios. Mitigated by 
  Docker Compose and by keeping Redis's role narrow (idempotency only).
- Lombok introduces a compile-time annotation-processing step that some 
  teams dislike. Accepted as an industry-standard trade-off.
- JWT self-issued auth is less production-realistic than delegating to 
  an external identity provider. Documented as an intentional scope 
  limit; the extension path is called out in interviews.

### Neutral

- Testcontainers requires Docker to be installed locally and in CI. 
  This is the standard baseline for modern Java development.
- Flyway migrations become an append-only history; renaming or reordering 
  is not straightforward once a migration has run in any environment. 
  This is a general property of migration tooling, not a project risk.
- The stack is intentionally conventional. There is no bet on a niche or 
  experimental library. This maximizes credibility and minimizes surprise 
  during implementation.