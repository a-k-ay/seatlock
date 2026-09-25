# Phase 5 — Security

**Status:** Complete  
**Dates:** 2026-09-24  
**Related ADRs:** ADR-005 (Tech Stack), ADR-008 (JWT Auth Architecture)

## Purpose

Phase 5 adds authentication so that every hold, booking, and payment 
downstream can be attributed to a specific user. Without it, "who owns 
this hold?" is unanswerable, and the concurrency story in Slice 3 
loses meaning.

## What Phase 5 Established

- Spring Security is wired end-to-end.
- `POST /auth/login` accepts email + password, returns a signed JWT.
- Every non-public endpoint requires `Authorization: Bearer <token>`.
- Passwords are stored as BCrypt hashes.
- Test users are seeded at startup via `ApplicationRunner`.
- Swagger UI has an "Authorize" button so the flow is demoable in the 
  browser.

## Files Added

Under `com.seatlock.auth`:
- `User` (JPA entity for the existing `users` table)
- `UserRepository`
- `LoginRequest`, `LoginResponse` (records)
- `JwtService` (issues and validates tokens)
- `AuthController` (`POST /auth/login`)
- `JwtAuthenticationFilter` (runs on every request)
- `SecurityConfig` (filter chain + `PasswordEncoder` bean)
- `UserSeeder` (idempotent seeding at startup)

Under `com.seatlock.config`:
- `OpenApiConfig` (declares the Bearer security scheme for Swagger UI)

Configuration:
- `pom.xml` — added `spring-boot-starter-security`, 
  `jjwt-api/impl/jackson` 0.12.6
- `application.properties` — added `seatlock.jwt.secret` and 
  `seatlock.jwt.expiration-minutes`

## Auth Flow — Login

1. Client sends `POST /auth/login` with `{ email, password }`.
2. `AuthController` looks up the user by email via `UserRepository`.
3. `PasswordEncoder.matches(rawPassword, user.getPasswordHash())`.
4. On success, `JwtService.issueToken(user)` returns a signed JWT.
5. Client receives 
   `{ token, tokenType: "Bearer", expiresInSeconds: 3600 }`.
6. On failure (user missing or password mismatch): 401 with the same 
   generic error, to prevent user enumeration.

## Auth Flow — Protected Request

1. Client sends any protected endpoint with 
   `Authorization: Bearer <token>`.
2. `JwtAuthenticationFilter` runs first in the filter chain:
   - Extracts the `Bearer ` prefix from the header.
   - Validates the signature and expiration via `JwtService`.
   - Extracts the user UUID from the token's subject claim.
   - Populates `SecurityContextHolder` with a 
     `UsernamePasswordAuthenticationToken` carrying the user id.
3. Spring Security's `authorizeHttpRequests` check runs:
   - If the request path matches a `permitAll` matcher → allow.
   - Otherwise require an authenticated principal → allow.
4. Request reaches the controller with the user id available from 
   `SecurityContextHolder`.
5. If the token is missing, expired, or invalid → 
   `SecurityContextHolder` stays empty → Spring returns 403 (or 401 
   once we configure the entry point).

## Public Endpoints (No Token Required)

- `POST /auth/login`
- `GET /actuator/health`
- Swagger UI at `/swagger-ui/**`
- OpenAPI JSON at `/v3/api-docs/**`

## Protected Endpoints (Bearer Token Required)

- All `/events/**` (and nested `/seats`)
- Everything added in subsequent slices (holds, bookings, payments)

## Test Credentials

Seeded automatically on first boot. Idempotent — reboot does not 
duplicate.

- `alice@seatlock.dev` / `alice123`
- `bob@seatlock.dev` / `bob123`
- `admin@seatlock.dev` / `admin123`

## Configuration Reference

`application.properties`:

seatlock.jwt.secret=<32+ char string>
seatlock.jwt.expiration-minutes=60


Production would source the secret from an environment variable or 
secrets manager. Documented as a follow-up.

## Explicit Non-Goals (from ADR-005 and ADR-008)

- User registration
- Password reset
- Refresh tokens
- Roles / permissions beyond authenticated
- Social login (Google, GitHub, etc.)
- Rate limiting on the login endpoint

Each is a talking point for interviews, not code.

## Follow-Ups (Tracked, Deferred)

- Configure `AuthenticationEntryPoint` so unauthenticated requests 
  return 401 instead of 403.
- Rate-limit `/auth/login` (Bucket4j or Redis-backed token bucket).
- Move JWT secret to environment variable.
- Refresh-token flow with Redis rotation.
- Consider Argon2id password hashing for production posture.

## What's Next — Slice 3: Holds

The concurrency crown jewel. Every hold now knows which user placed 
it, thanks to Phase 5. Slice 3 will:

- Add `POST /holds` with an idempotency key header.
- Add `DELETE /holds/{id}` for explicit release.
- Build the hold-expiry background worker.
- Write the concurrency integration test that fires 50 concurrent 
  hold requests for the same seat and asserts exactly one wins. This 
  test is what turns ADR-006 from a design document into a proven 
  guarantee.
  
  