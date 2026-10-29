# ShipFlow

A backend for multi-carrier delivery. Stores book shipments, ShipFlow picks a carrier for each parcel, books it,
takes scan events, deals with failed deliveries and COD cash, and plans rider routes. I built it around the
problems a GCC e-commerce delivery platform runs into: carrier APIs that flake out, scan streams with duplicate
and out-of-order events, carriers that oversell their capacity, and COD money that goes missing.

Spring Boot 3.5, Java 21, PostgreSQL 17. It's one deployable with clear module boundaries.
Design notes and ADRs are in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), and the measured numbers are in
[docs/PERFORMANCE.md](docs/PERFORMANCE.md).

## What it does

- **Booking.** `POST /api/v1/shipments` with an `Idempotency-Key`. Replaying the same key returns the same
  shipment. Reusing the key with a different body returns 422. There's also bulk CSV upload with per-row errors,
  plus Shopify and generic JSON order adapters.
- **Carrier allocation.** Hard filters run first: COD support, weight, an open circuit breaker, daily capacity.
  Then weighted rules score cost, SLA, capacity headroom, on-time rate, first-attempt success and RTO rate. Every
  decision is stored with a readable explanation (`GET /shipments/{id}/allocation`).
- **Capacity.** One conditional UPSERT plus a CHECK constraint, so a carrier can't be oversold.
- **Flaky carriers.** Each carrier call gets a timeout, a Resilience4j retry for transient errors (2 attempts with
  jittered backoff) and a per-carrier circuit breaker. If a booking fails, it fails over to the next-best carrier.
  Carrier calls never run inside a DB transaction. A timeout counts as "uncertain" and gets reconciled later
  instead of triggering a failover, so you don't end up with duplicate consignments.
- **Scans.** Dedupe on the carrier's event id, ordering by event time and rank, inferred intermediate states,
  and optimistic locking on the shipment.
- **NDR / RTO.** Reattempt policies, automatic RTO and a report.
- **COD.** Upload remittances, then reconcile them as MATCHED / SHORT / EXCESS / MISSING.
- **Webhooks.** A transactional outbox with HMAC-SHA256 signed deliveries, backoff, a DLQ with replay, and
  `SKIP LOCKED` workers.
- **The extras.** Self-learning carrier scorecards, an explainable 0–100 COD risk score, promised delivery dates
  that account for hub cut-offs and holidays, and rider routing (sweep clustering, nearest neighbour, 2-opt).

The production plumbing:
- JWT roles (MERCHANT / OPS / ADMIN / RIDER)
- RFC 7807 errors with stable codes
- Flyway migrations
- Pagination
- Actuator liveness and readiness (readiness includes the DB)
- Micrometer/Prometheus metrics
- JSON logs with a correlation id (`X-Correlation-Id` goes in, comes back out and appears on every log line,
  including log lines from carrier calls)
- Graceful shutdown

## Run it

You need Docker. JDK 21 and Maven 3.9 are only needed if you want to build outside Docker.

```bash
export JWT_SECRET="$(openssl rand -base64 48)"   # required, the app won't start without it
docker compose up -d --build --wait              # app on localhost:7300, postgres on localhost:55460
```

Or run the whole walkthrough. It builds the image, seeds a small Dubai network and runs these steps against the
live API:
- booking and idempotency
- validation errors
- the allocation explanation
- bulk CSV
- scans and tracking
- COD reconciliation
- role checks
- retries on a flaky carrier
- failover when a carrier is down
- correlation ids and metrics
- a small load test

```bash
scripts/demo.sh            # leaves the stack running
KEEP=0 scripts/demo.sh     # stops it at the end (you'll see the graceful shutdown in the logs)
```

Sample output from a real run is in [docs/demo-output.txt](docs/demo-output.txt).
To get tokens by hand: `scripts/dev-jwt.sh MERCHANT <merchant-id>`, `scripts/dev-jwt.sh OPS`,
`scripts/dev-jwt.sh ADMIN`. They use the same `JWT_SECRET`.

Config comes from env vars:
- `DB_URL`, `DB_USER`, `DB_PASSWORD`
- `JWT_SECRET` (at least 32 bytes), `JWT_ISSUER`
- `PORT` (default 7300)
- `SHUTDOWN_GRACE` (default 20s)
- `SHIPFLOW_SCHEDULING_ENABLED`

Everything else is in `application.yml`.

## Tests

```bash
mvn -B verify                         # unit tests + integration tests on real Postgres via Testcontainers
TEST_REQUIRE_DB=true mvn -B verify    # fail (instead of skip) when no database is available, which is what CI does
```

There are about 100 unit tests and about 50 integration tests, and the ITs always use real Postgres, never H2. They
cover concurrency (200 threads racing for capacity), failover, webhooks and scan races. ArchUnit rules keep the
layering honest:
- the domain doesn't touch Spring/JDBC
- controllers don't touch JdbcTemplate
- only the carrier module calls carrier APIs
- no field injection

CI (GitHub Actions) does three things:
- builds and runs all tests
- builds the Docker image
- runs a Trivy scan for HIGH/CRITICAL dependency CVEs

## Numbers

These are dev numbers from one shared 8 vCPU box, with the client, the app and Postgres all on the same machine.
Other projects were running on it too, so read them as "it works under load", not as a capacity plan.

- HTTP, end to end (`scripts/loadtest.py`, 400 bookings, 16 concurrent clients, app capped at 512 MB heap):
  112–198 req/s across four runs on Oct 29, 2026, with p95 151–256 ms and every request returning 201.
- In-process benchmark, no HTTP (`scripts/run-benchmark.sh`): about 310–430 bookings/s and 1,500–1,900 scans/s
  at 50–100 threads. The invariant checks all came back 0: no overbooking, no duplicate consignments, no
  double-applied scans.
- Routing: 2-opt tours are 82–91% shorter than the naive order for 100–2,000 stops, in under 6 ms.

The details and how to reproduce them are in [docs/PERFORMANCE.md](docs/PERFORMANCE.md).

## What's missing

- **Carriers are simulated.** There's a `CarrierPort` interface and a simulator with OK / SLOW / FLAKY / FAIL
  modes, but no real carrier integration.
- **JWT is HS256 with a shared secret.** A real deployment should validate against an IdP's JWKS. There's also no
  token issuing endpoint; `scripts/dev-jwt.sh` is for local use only.
- **Only tested as a single node.** The webhook dispatcher and stats flush claim work with `SKIP LOCKED`, but I
  haven't run more than one instance, and the other scheduled jobs don't have a leader lock.
- **No Prometheus/Grafana in compose.** The `/actuator/prometheus` endpoint exists but needs an ADMIN token, and
  there are no dashboards or alerts.
- **No Kubernetes manifests or Helm chart.** It's Docker Compose only.
- **Two known spring-webmvc 6.2 CVEs.** CVE-2026-47890 and CVE-2026-47884 are only fixed in Spring 7 / Boot 4.
  They're listed in `.trivyignore`; neither affected feature (SSE, XsltView) is used here.
- **No load testing beyond one box.** There's no soak test either.
- **No rate limiting on the public API.**
