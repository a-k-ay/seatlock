# Phase 8 — DevOps: Containerization + VPS Deployment

**Status:** Complete
**Dates:** 2026-09-30
**Related ADRs:** ADR-009 (Deployment Architecture)

## Purpose

Phase 8 takes SeatLock from "runs on my laptop" to "runs on the public
internet at a stable, HTTPS-protected URL that anyone can hit." The
whole point of a portfolio backend is that the reviewer can actually
try it. Without deploy, the concurrency claim of ADR-006 is a story;
with deploy, it's a URL.

## What Phase 8 Established

- Multi-stage `Dockerfile` builds the app with full JDK+Maven, then
  ships only Alpine JRE + the compiled jar (~200 MB final image)
- The container runs as a non-root user for security posture
- Container-aware JVM sizing (`MaxRAMPercentage=75.0`)
- `docker-compose.yml` brings up Postgres 16 + Redis 7 + Spring Boot
  as one stack; Postgres has a healthcheck that gates app startup
- The same compose file runs locally and on the VPS with no
  modifications — env vars drive the environment-specific differences
- Ubuntu 24.04 LTS VM provisioned on GCP e2-micro free tier
- Host-level hardening: UFW firewall (deny incoming by default,
  allow 22/80/443), sudo via IAM role (GCP OS Login), password
  and root SSH already disabled by cloud image defaults
- Docker Engine installed from Docker's official apt repo (not
  Ubuntu's stale package)
- Nginx installed on the VM, reverse-proxies port 80/443 to the
  app on `localhost:8080`; the app is NOT directly reachable from
  the internet
- Duck DNS free subdomain (`seatlock.duckdns.org`) pointed at the
  VM's external IP
- Let's Encrypt TLS cert obtained via Certbot (ACME HTTP-01
  challenge); HTTP redirects to HTTPS via a Nginx 301
- Certbot's systemd timer handles auto-renewal (twice daily,
  actually renews at 60 days remaining)

## Files Added

At repo root:
- `Dockerfile` — multi-stage: `maven:3.9-eclipse-temurin-21` build
  stage, `eclipse-temurin:21-jre-alpine` runtime stage
- `.dockerignore` — keeps build context tiny (excludes `target/`,
  `.git/`, `docs/`, IDE files)

Updated:
- `docker-compose.yml` — added `seatlock` service (built from
  Dockerfile), Postgres healthcheck, `depends_on: service_healthy`,
  env vars for `SPRING_PROFILES_ACTIVE=prod` and DB/Redis URLs

## Local development workflow — two modes

**Hybrid dev** (fast iteration during coding):
```bash
docker compose up -d postgres redis   # infra only
mvn spring-boot:run                   # app on host JVM
```
- App reboot is ~15s (JVM only, not a docker build)
- Save code → Ctrl+C → mvn → new code running
- Uses `localhost:5433` and `localhost:6379` for DB and Redis

**Full containerized** (verifies the shippable shape):
```bash
docker compose up --build
```
- All three services in containers
- First build ~5 min (Maven downloads deps, compiles, packages jar)
- Uses internal Docker network hostnames (`postgres:5432`)
- Activates `prod` profile → JSON logs

## Deploy walkthrough — what the DevOps journey covered

### Phase A: Provisioning
- Google Cloud Free Tier signup (Always Free e2-micro, not the
  12-month trial)
- Ubuntu 24.04 LTS Minimal on 30 GB Standard persistent disk
  (Balanced disk is not free — the type matters)
- Region: `us-central1` (one of the three free-tier regions)
- Firewall: "Allow HTTP" and "Allow HTTPS" checked at creation

### Phase B: Server hardening
- GCP OS Login provides SSH via Google-identity keys; password
  and root SSH already disabled by default
- Added `Compute OS Admin Login` IAM role for sudo (Owner is not
  enough — this is a fine-grained GCP permission)
- Installed and enabled UFW: default deny incoming, allow
  22/80/443/tcp
- Two firewall layers now: GCP VPC firewall at the network edge,
  UFW on the host itself. Defense in depth

### Phase C: Docker install
- Followed Docker's official Ubuntu install path — GPG key, apt
  repo, `docker-ce docker-ce-cli containerd.io docker-buildx-plugin
  docker-compose-plugin`
- Verified with `docker run hello-world`
- Added user to `docker` group so daily commands don't need `sudo`

### Phase D: App deploy
- `git clone https://github.com/a-k-ay/seatlock.git` into `/opt/seatlock`
- Initial deploy exposed the app on port 80 directly (quick sanity
  check before Nginx)
- `docker compose up --build -d` — Postgres came up healthy first,
  then the app (~5 min startup on 1 GB RAM)

### Phase E: Nginx + HTTPS
- Rebound app to `127.0.0.1:8080:8080` (internal only)
- Installed Nginx, created a server block for
  `seatlock.duckdns.org` with `proxy_pass http://localhost:8080`
  and standard `X-Forwarded-*` headers
- Registered `seatlock` on Duck DNS pointed at the VM's external IP
- Installed Certbot + nginx plugin, ran
  `certbot --nginx -d seatlock.duckdns.org`
- Certbot got the cert but couldn't auto-install because the
  server block had a placeholder `YOUR-ACTUAL-SUBDOMAIN` — fixed
  the placeholder, ran `certbot install --cert-name seatlock.duckdns.org`
- Certbot added the port 443 server block, added HTTP→HTTPS 301
  redirect via an `if ($host = ...)` clause in the port 80 block
- Verified `certbot renew --dry-run` succeeds — renewal is automated

## Interview-speakable summary of the whole journey

> "I containerized the app with a multi-stage Dockerfile that ships
> Alpine JRE with just the jar. Docker Compose runs the full stack —
> Postgres, Redis, Spring Boot — locally and in production with no
> config drift, since env vars drive the environment-specific pieces.
>
> Deploy is on a GCP free-tier VPS I hardened myself: two firewall
> layers (VPC + UFW), OS Login sudo via IAM role, no password SSH.
> Nginx terminates TLS on 443 using a Let's Encrypt cert issued via
> Certbot's HTTP-01 challenge; the Spring app listens only on
> localhost so the reverse proxy is the only way in. HTTP redirects
> to HTTPS via a Nginx 301, and Certbot's systemd timer auto-renews
> the cert at 60 days remaining."

## Configuration Reference

`Dockerfile`:

    FROM maven:3.9-eclipse-temurin-21 AS build
    ...
    FROM eclipse-temurin:21-jre-alpine
    ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -Duser.timezone=UTC"

`docker-compose.yml` (prod deploy overrides):

    SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/seatlock
    SPRING_DATA_REDIS_HOST: redis
    SPRING_PROFILES_ACTIVE: prod

Nginx `/etc/nginx/sites-available/seatlock`:

    listen 443 ssl;
    server_name seatlock.duckdns.org;
    proxy_pass http://localhost:8080;
    ssl_certificate /etc/letsencrypt/live/seatlock.duckdns.org/fullchain.pem;

## Explicit Non-Goals

- **CI/CD pipeline** — deploy is manual (`git pull && docker compose
  up -d` on the VM). GitHub Actions on push is a follow-up
- **Multi-region / HA** — one VM, one region. Real prod would front
  two VMs with a load balancer
- **Automated backups** — no `pg_dump` cron yet
- **Zero-downtime deploys** — `docker compose up -d` briefly stops
  the old container; ~30s downtime per deploy
- **Log shipping to an external service** — logs live in
  `docker logs`; a real product would ship them
- **Secrets in a vault** — env vars in `docker-compose.yml` are the
  secret store; production would use a proper KMS / Vault
- **Metrics scraping** — `/actuator/prometheus` is exposed but no
  Prometheus + Grafana is scraping. Docker-compose addition is a
  follow-up

## Follow-Ups (Tracked, Deferred)

- GitHub Actions: run `mvn test` on every push, build image on every
  push to main, optionally push image to GHCR
- Simple `pg_dump` cron with a rotating retention window
- `restart: unless-stopped` on all services in docker-compose so
  they survive VM reboots automatically (currently manual)
- Move secrets from `docker-compose.yml` env block to a
  `.env` file gitignored; document the required env vars in README
- Add Prometheus + Grafana containers to `docker-compose.yml` for
  a self-contained observability stack; expose Grafana on a
  subpath behind Nginx
- Rebuild the VM once with `enable-oslogin=FALSE` and standard
  SSH keys — documented the OS Login sudo flakiness the deploy
  hit and the `sudo -i` workaround; a bare-SSH-key setup avoids it

## Validation Ledger

| Guarantee | Where proven |
|-----------|--------------|
| Same docker-compose runs locally and prod | Local `docker compose up --build` and VM `docker compose up --build` both produce a running stack |
| App is not directly reachable | `curl http://<VM-IP>:8080/actuator/health` from outside fails (only 80/443 open); `curl localhost:8080/actuator/health` on the VM succeeds |
| HTTPS with green padlock | Browser at `https://seatlock.duckdns.org/actuator/health` shows a valid Let's Encrypt cert |
| HTTP → HTTPS redirect | `curl -I http://seatlock.duckdns.org/` returns 301 to https |
| Auto-renewal works | `certbot renew --dry-run` succeeds |
| Nginx is the only public entry | `docker ps` shows `127.0.0.1:8080` binding, not `0.0.0.0:8080` |

## What's Next

SeatLock is complete as a portfolio piece. Potential future work
sits in the follow-up sections of each phase summary. The obvious
next capability that would make SeatLock a real product — not a
demo — is payment integration, which would extend
`ADR-007 (confirm-booking)` into an actual payment gateway
integration.