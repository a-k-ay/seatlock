The 0-to-N Steps for Any Backend Project

These are universal. Language doesn't matter — the SEQUENCE and QUESTIONS are the same.

Phase 1 — Think (before writing code)
Understand the problem — What breaks if I don't build this? Who uses it? What's the top-3 hardest thing?
Choose architecture — Monolith? Microservices? Modular monolith?
Choose tech stack — Language, framework, database, cache, build tool. Defend each pick.
Design data model — Entities, relationships, constraints. Draw it.
Design APIs — Endpoints, request/response shapes, error codes.
Phase 2 — Set Up (once per project)
Install runtime & tools — JDK/Node/Python, IDE, Docker.
Bootstrap the project skeleton — Framework CLI (Spring Initializr, django-admin, npx create-nest-app).
Set up dependencies — DB + cache in Docker containers.
Wire configuration — Connection strings, env vars.
Verify walking skeleton — App boots, connects to DB, health endpoint responds.
Phase 3 — Build slice-by-slice (repeats per feature)

For each entity/feature:
11. Write database migration — CREATE TABLE ...
12. Write entity — code representation of the table
13. Write repository — data access layer
14. Write DTOs — request and response shapes
15. Write service — business logic
16. Write controller — HTTP endpoints
17. Write test — verify it works
18. Commit + push

Phase 4 — Harden (once app is working)
Add authentication — JWT, OAuth, sessions
Add observability — logs, metrics, traces
Set up CI/CD — automated tests + deploys
Deploy — to a real environment
Part 3: New Project vs Existing Project
New project from scratch: start at Step 1.
Existing project (job / take-over): skip Steps 1-10, jump into Phase 3 slice-by-slice. Read the existing code to understand the current state, then add your slice using the same layered pattern.
Part 4: What Changes Across Languages
Concepts (all layers, patterns, idempotency, transactions, concurrency) → same everywhere.
Syntax + tooling (annotations, package managers, config) → different per language.
