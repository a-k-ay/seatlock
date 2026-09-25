# ADR-008: JWT Authentication Architecture

**Status:** Accepted  
**Date:** 2026-09-24

## Context

ADR-005 chose "Spring Security + self-issued JWT, minimal scope" as the 
authentication approach. This ADR pins the specific implementation 
decisions that came out of Phase 5:

- Which signing algorithm to use.
- Token lifetime and refresh strategy.
- Password hashing scheme.
- Whether to build user registration, refresh tokens, roles.
- How the API surface separates public and protected paths.

The overriding constraint: the auth layer must be defensible in an 
interview without becoming the story of the project. The concurrency 
story is what SeatLock is really demonstrating.

## Decision

**Signing algorithm:** HS256 (HMAC with SHA-256). Symmetric secret 
shared only within the single service.

**Token lifetime:** 60 minutes. No refresh tokens.

**Token payload:**
- Subject: user UUID.
- Custom claim: email.
- Standard claims: issued-at, expiration.

**Password hashing:** BCrypt (Spring's `BCryptPasswordEncoder`, default 
strength 10).

**User provisioning:** hardcoded seeded users via 
`ApplicationRunner` at startup. No registration endpoint.

**Session policy:** `SessionCreationPolicy.STATELESS`. Every request 
carries its own JWT; the server holds no HTTP session state.

**Public paths (no token required):**
- `/auth/**`
- `/actuator/health`
- `/swagger-ui/**`, `/swagger-ui.html`, `/v3/api-docs/**`

**All other paths:** require a valid `Authorization: Bearer <token>` 
header. The `JwtAuthenticationFilter` extracts the user id and places 
it into `SecurityContextHolder`.

**Swagger UI integration:** an `OpenApiConfig` class declares a 
`bearerAuth` security scheme so Swagger UI renders the "Authorize" 
button. Callers paste the token once and every subsequent request from 
Swagger includes the header.

## Alternatives Considered

**RS256 (asymmetric public/private key)**  
Rejected. RS256 shines when a *different* service (e.g. an API 
gateway) validates tokens issued by an identity service. SeatLock is a 
single monolith. HS256 with a shared secret is simpler with no 
security downside inside a single trust boundary.

**OAuth2 / OpenID Connect with an external issuer (Keycloak, Auth0)**  
Rejected. Adds an external service, redirect flows, client registration 
paperwork. Overkill for a demo. Retained as a talking point for 
interviews: "in production I would delegate to Auth0 and use Spring 
Security's OAuth2 Resource Server."

**Refresh tokens**  
Rejected for scope. A production system would store rotating refresh 
tokens in Redis with a 7-day TTL, one active per user, invalidated on 
each refresh. This is documented so the trade-off is visible; not built.

**Longer or shorter access-token lifetime**  
60 minutes is the pragmatic middle ground: short enough to limit blast 
radius if a token is exposed, long enough that a demo user is not 
constantly re-logging-in during Swagger testing.

**Argon2id password hashing**  
Argon2id is cryptographically stronger but Spring's default of BCrypt 
is widely supported, matches interview expectations, and is 
sufficient for the scope. Would revisit for a real production system.

**Session-based authentication (cookies)**  
Rejected. REST APIs are stateless; introducing sessions couples us to 
sticky sessions or a distributed session store, both of which would 
overshadow the concurrency story.

**Roles or permissions beyond "authenticated"**  
Rejected. SeatLock has one class of user. Adding roles would create 
work with no correctness gain.

**User registration endpoint**  
Rejected. Would require email verification, rate limiting, CAPTCHA, 
password-strength rules — a whole feature area orthogonal to the 
concurrency story.

## Consequences

### Positive

- Simple, well-understood JWT flow. Interviewers recognize the pattern.
- Stateless — trivially horizontally scalable.
- Auth is decoupled from external identity services.
- BCrypt has slow-hashing built in, defeating basic brute-force.
- Bearer-token pattern integrates directly with Swagger UI, curl, 
  Postman.
- The seeded-user approach makes the API demoable in under 60 seconds.

### Negative

- No token revocation. A stolen token remains valid until its 60-min 
  expiry. Mitigated by short lifetime; would need a blacklist store 
  (Redis) for real revocation.
- No refresh flow means users re-authenticate every hour.
- JWT secret is stored in `application.properties`, which is 
  demo-appropriate but not production-grade. Would move to environment 
  variable + secrets manager.
- No rate limiting on `/auth/login`. Vulnerable to brute-force in 
  principle; BCrypt's slow-hashing softens the blow. Rate limiting is 
  a follow-up.

### Neutral

- HS256 requires every verifier to share the same secret. In a 
  monolith, this is inside one process; no distribution problem.
- The "Authorize" button in Swagger UI is a Swagger-only convenience 
  for demos; production clients would send the header directly.
- The seeded users pattern relies on `ApplicationRunner`. Fine for 
  local dev; a real environment would use a controlled admin process.

## Follow-Ups (Explicitly Deferred)

- Configure `AuthenticationEntryPoint` so unauthenticated requests 
  return `401 Unauthorized` instead of Spring Security 6's default 
  `403 Forbidden`. Semantic correctness.
- Refresh-token flow with Redis-backed rotation.
- Rate limiting on `/auth/login` (e.g., Bucket4j or Redis token bucket).
- Move JWT secret to environment variable + secrets manager.
- Optional: OAuth2 Resource Server config so the same service can 
  accept externally issued tokens in future.