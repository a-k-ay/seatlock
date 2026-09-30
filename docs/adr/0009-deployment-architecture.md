# ADR-009: Deployment Architecture — Docker Compose on VPS

**Status:** Accepted — implemented Phase 8
**Date:** 2026-09-30
**Related ADRs:** ADR-005 (Tech Stack)

## Context

SeatLock needed to be deployed somewhere reachable on the public internet
to serve as a portfolio demo. The choice of *how* to deploy carries real
architectural implications, cost implications, and — most importantly for
a portfolio project — different lessons the deployer can speak to.

Broad options considered:

1. **Managed PaaS** (Railway, Render, Fly.io)
2. **Serverless containers** (Google Cloud Run, AWS Fargate)
3. **Managed Kubernetes** (GKE, EKS, AKS)
4. **Self-managed VPS** running Docker Compose behind Nginx
5. **Self-managed VPS** running the app directly via systemd (no Docker)

## Decision

Deploy via **Docker Compose on a self-managed Ubuntu VPS**, with **Nginx**
as reverse proxy and **Let's Encrypt** for TLS.

Concretely:
- One Ubuntu 24.04 LTS Minimal VM (GCP e2-micro free tier, us-central1)
- Docker Compose runs three containers on a shared network:
  Spring Boot app, Postgres 16, Redis 7
- The app binds only to `127.0.0.1:8080` inside the VM — not reachable
  from the internet directly
- Nginx binds to `:80` and `:443`, reverse-proxies to the app on
  `localhost:8080`
- HTTPS via Let's Encrypt cert, issued through Certbot's HTTP-01
  challenge, auto-renewed by certbot's systemd timer
- HTTP requests are 301-redirected to HTTPS
- Free subdomain from Duck DNS (`seatlock.duckdns.org`) points at the
  VM's external IP

## Rationale

**Why not PaaS (Railway, Render, Fly.io)?**
Convenient — one command deploys — but abstracts away the entire
Linux/networking/TLS stack. The point of this deployment was to build
DevOps skills the deployer could speak about in interviews: server
hardening, firewall layering, Nginx configuration, TLS via ACME. PaaS
hides all of that.

**Why not serverless (Cloud Run, Fargate)?**
Serverless containers are excellent for stateless HTTP services with
bursty traffic. SeatLock has a background worker (hold expiry) that must
run continuously — serverless would either idle the worker (breaking
expiry semantics) or charge for always-on execution (defeating the
"free" premise). Also: cold-start latency would hide the ADR-006
concurrency story behind a 10-second first-request delay.

**Why not Kubernetes?**
For a single-node, three-container demo, Kubernetes is theatrical
complexity. Nothing in SeatLock benefits from K8s primitives (no
HPA, no rolling deploys across nodes, no service mesh). Would be
resume-padding, not learning.

**Why not systemd + bare metal Java?**
Would require re-building the app's system dependencies (Java 21, JDBC
drivers) each time. Docker's whole value is that `docker compose up`
produces the same result on the developer's laptop, in CI, and on
production. Losing that means losing the "works on my machine" defense.

**Why VPS + Docker Compose specifically?**
- Same `docker-compose.yml` runs locally and in production — zero
  drift risk between environments
- Full root access to a real Linux box — exposes the deployer to
  firewalls, systemd, reverse proxies, TLS, package management, log
  management. All the skills a "did you deploy anything yourself?"
  interview question is really probing
- Free forever on GCP's Always Free tier (e2-micro)
- Trivially portable to any other VPS provider (Hetzner, DigitalOcean,
  Oracle Cloud) — same commands, same config

## Consequences

**Positive:**
- The deployer can honestly say "I hardened, deployed, and operate this
  myself" — every component is under their control
- HTTPS + custom domain make the demo URL professional
- Auto-renewing certs mean the demo doesn't rot in six months
- Portable — if GCP's free tier ever changes, the same setup runs on
  any Linux host with 1 GB RAM

**Negative:**
- Slow startup on e2-micro (~5 minutes for Spring Boot to boot inside
  1 GB RAM) — acceptable for demo, not production traffic
- Single-node; no automatic failover if the VM crashes
- Backups are not automated (would need a cron with `pg_dump` for a
  real product)
- Software updates on the host OS are manual (`apt upgrade` from
  time to time)

**Neutral:**
- The three containers share the VM's memory; Postgres + Redis + JVM
  compete for the ~800 MB usable RAM after OS overhead. Fine for demo
  load; would need a bigger VM for real traffic

## Non-goals

Explicitly not built as part of this ADR:
- Zero-downtime deploys (would need blue/green or rolling under a
  load balancer)
- Automated backups (documented as a follow-up)
- Log aggregation to an external service (Datadog, Loki) — logs live
  in `docker logs` on the VM
- CI/CD pipeline that pushes on every git push (a `git pull &&
  docker compose up -d` on the VM is a manual step)
- Multi-region deploy

## Alternatives revisited later

If SeatLock ever grew beyond a portfolio demo:
- Move to Cloud Run for the app (keep managed Postgres in Cloud SQL)
  for autoscaling and zero-ops
- Or graduate to K8s if multiple services accumulate
- Or stay on VPS but add a second node with a floating IP for HA

None of these are needed today.

## Validation

The live URL — https://seatlock.duckdns.org/swagger-ui/index.html —
is the validation. Every endpoint documented in Swagger, from `/auth/login`
through `/holds/bulk` to `/actuator/prometheus`, runs from this deploy.