# ShipFlow – carrier allocation & last-mile delivery engine

Backend-only Spring Boot 3.3 / Java 21 / PostgreSQL 17 service modelled on the problems a multi-carrier delivery
SaaS for GCC e-commerce has to solve: book shipments from many stores, pick the right carrier per parcel, survive
flaky carrier APIs, ingest messy scan streams, handle failed deliveries and COD cash, tell customers a promise
date, and plan rider routes.

- Architecture, extension contracts, ER / state / sequence diagrams, ADRs: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
- Benchmarks (method, raw numbers, interpretation, before/after): [docs/PERFORMANCE.md](docs/PERFORMANCE.md)
- Resume bullets + interview Q&A: [docs/RESUME.md](docs/RESUME.md)

## Domain problem → where it is solved

| problem | ShipFlow answer | code |
|---|---|---|
| Stores retry requests / double-click "ship" | `Idempotency-Key` claim (own tx) + same-tx completion, payload-hash check, replay | `idempotency/IdempotencyService` |
| Bulk uploads, many store platforms | CSV upload with per-row errors (column named), `OrderAdapter` per platform (Shopify, generic JSON) | `booking/application/BulkUploadService`, `booking/adapter` |
| Which carrier for this parcel? | hard filters (COD, weight, breaker, capacity …) + weighted rules (cost, SLA, headroom, on-time, first-attempt, RTO) with a stored explanation | `allocation` |
| Carrier daily capacity must never be oversold | single conditional UPSERT + CHECK; reserve-last optimistic allocation | `carrier/capacity`, `AllocationService` |
| Carrier API down / slow | circuit breaker + timeout, failover to next-best carrier, no carrier call inside a tx, timeouts reconciled (no duplicate consignments) | `carrier/resilience`, `booking/carrier` |
| Duplicate / late / out-of-order scans | dedupe on carrier event id, event-time + rank rules, inferred intermediate states, optimistic locking | `scan` |
| Failed deliveries (NDR) | reattempt policy chain, auto-RTO, NDR report | `ndr` |
| COD cash leakage | remittance upload + reconciliation MATCHED / SHORT / EXCESS / MISSING | `cod` |
| Merchants need events | transactional outbox, HMAC-SHA256 signed webhooks, backoff + jitter, DLQ + replay, SKIP LOCKED workers | `webhook` |
| **A** carriers differ in quality | self-learning rolling scorecards (Bayesian-smoothed) feed allocation | `stats`, `scorecard`, `allocation/rules` |
| **B** carrier outages | breaker-driven failover without duplicate bookings | `booking/carrier/BookingOrchestrator` |
| **C** COD refusals / bad addresses | explainable 0-100 risk score + actions (OTP, verification call, prepaid nudge) | `risk` |
| **D** "when will it arrive?" | EDD from hub cut-off, weekends/holidays per country, lane lead-time P80 | `edd` |
| **E** rider routing | sweep clustering + nearest neighbour + 2-opt vs naive baseline | `routing` |

## Run locally

Requirements: JDK 21, Maven 3.9, PostgreSQL 17 (local install or Docker).

```bash
# PostgreSQL (creates shipflow, shipflow_test, shipflow_bench)
docker compose up -d                      # or use an existing local Postgres and run scripts/init-test-db.sql

# app (dev profile = local DB password default + throwaway dev JWT secret; override both outside a laptop)
export JWT_SECRET="$(openssl rand -base64 48)"   # the app and scripts/dev-jwt.sh must share it
mvn -B spring-boot:run -Dspring-boot.run.profiles=dev
# Flyway migrates on start; then seed a tiny Dubai network:
psql -h localhost -U postgres -d shipflow -f scripts/seed-dev.sql
```

### Configuration (env → property)

| env | default | meaning |
|---|---|---|
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | `jdbc:postgresql://localhost:5432/shipflow`, `postgres`, *(empty; dev profile: local default)* | datasource |
| `JWT_SECRET` (required, ≥ 32 bytes), `JWT_ISSUER` | – | HS256 JWT validation |
| `SHIPFLOW_SCHEDULING_ENABLED` | `true` | reconciler, webhook dispatcher, stats aggregator, COD job, idempotency purge |
| `shipflow.allocation.weights.*` | cost .35, sla .20, capacity .10, on-time .15, first-attempt .10, rto .10 | rule weights (0 disables) |
| `shipflow.allocation.reserve-last` / `optimistic-attempts` | `true` / `3` | optimistic capacity reservation |
| `shipflow.stats.flush-interval` / `batch-size` | `PT1S` / `5000` | write-behind statistics |

All tunables live in `application.yml` (`ShipFlowProperties`).

### Tests

```bash
mvn -B clean verify
```

Unit tests (surefire, `*Test`) + integration/API/concurrency tests (failsafe, `*IT`) on **real PostgreSQL** – never
H2. Database resolution: Testcontainers (`postgres:17-alpine`) if Docker is available → `TEST_DB_URL` /
`TEST_DB_USER` / `TEST_DB_PASSWORD` → local `jdbc:postgresql://localhost:5432/shipflow_test`. If none is reachable
the ITs are **skipped**, not failed.

### Benchmark

```bash
scripts/run-benchmark.sh                                   # booking, scan, routing at 50/100/200 threads
scripts/run-benchmark.sh -Dbench.scenarios=booking -Dbench.reserveLast=false   # reserve-first comparison
```

## API walkthrough (curl)

```bash
M=11111111-1111-1111-1111-111111111111                 # merchant from seed-dev.sql
MT=$(scripts/dev-jwt.sh MERCHANT $M); OPS=$(scripts/dev-jwt.sh OPS); ADM=$(scripts/dev-jwt.sh ADMIN)
H=http://localhost:8080/api/v1

# 1. idempotent booking (repeat the same command: same shipment id comes back)
curl -s -XPOST $H/shipments -H "Authorization: Bearer $MT" -H 'Idempotency-Key: ord-1001' \
  -H 'Content-Type: application/json' -d '{
    "merchantOrderRef":"ORD-1001","originHubCode":"DXB-HUB",
    "consignee":{"name":"Aisha Khan","phone":"+971501234567","addressLine1":"Villa 12, Street 4B, Al Barsha 2",
                 "landmark":"near Mall of the Emirates","city":"Dubai","pincode":"DXB-001"},
    "weightGrams":1200,"paymentMode":"COD","codAmount":{"amount":"250.00","currency":"AED"},
    "declaredValue":{"amount":"250.00","currency":"AED"}}'
# -> 201 with carrier, trackingNumber, awb, risk {score, band, factors[]}, edd {promisedDate, ...}

ID=...; TRK=...                                        # from the response
curl -s $H/shipments/$ID/allocation -H "Authorization: Bearer $MT"   # why this carrier (filters + rule scores)

# 2. bulk CSV
printf 'merchant_order_ref,origin_hub,name,phone,address_line1,city,pincode,weight_g,payment_mode,declared_value,currency\nORD-2001,DXB-HUB,Omar,+971501112233,Tower 3 Marina,Dubai,DXB-002,800,PREPAID,99.00,AED\n' |
  curl -s -XPOST $H/shipments/bulk -H "Authorization: Bearer $MT" -H 'Content-Type: text/csv' --data-binary @-

# 3. scans (ops endpoint; carriers use POST /carrier-webhooks/{code}/scans with X-Carrier-Signature HMAC)
CARRIER=...                                            # carrier.code from the booking response
for s in PICKED_UP OUT_FOR_DELIVERY DELIVERED; do
  curl -s -XPOST $H/scans -H "Authorization: Bearer $OPS" -H 'Content-Type: application/json' \
    -d "{\"carrierCode\":\"$CARRIER\",\"carrierEventId\":\"ev-$s\",\"trackingNumber\":\"$TRK\",\"eventCode\":\"$s\",\"occurredAt\":\"$(date -u +%FT%TZ)\"}"
done
# NDR: eventCode DELIVERY_FAILED + "ndrReason":"CUSTOMER_UNAVAILABLE" (policy may auto-RTO)

# 4. public tracking (no auth, no PII)
curl -s $H/tracking/$TRK

# 5. lists / history
curl -s "$H/shipments?status=DELIVERED&page=0&size=20" -H "Authorization: Bearer $MT"
curl -s $H/shipments/$ID/history -H "Authorization: Bearer $MT"

# 6. COD remittance + reconciliation (ops)
curl -s -XPOST $H/cod/remittances -H "Authorization: Bearer $OPS" -H 'Content-Type: application/json' \
  -d "{\"carrierCode\":\"$CARRIER\",\"remittanceRef\":\"R-1\",\"currency\":\"AED\",\"receivedAt\":\"$(date -u +%FT%TZ)\",\"lines\":[{\"trackingNumber\":\"$TRK\",\"amount\":\"250.00\"}]}"
curl -s -XPOST $H/cod/reconciliations -H "Authorization: Bearer $OPS" -H 'Content-Type: application/json' \
  -d "{\"carrierCode\":\"$CARRIER\",\"from\":\"$(date -u -d '-1 day' +%FT%TZ)\",\"to\":\"$(date -u -d '+1 day' +%FT%TZ)\"}"

# 7. scorecard, carrier outage simulation, route plan
curl -s "$H/admin/carriers/$CARRIER/scorecard?zone=DXB-Z1" -H "Authorization: Bearer $ADM"
curl -s -XPUT $H/admin/simulator/carriers/FASTX -H "Authorization: Bearer $ADM" -H 'Content-Type: application/json' -d '{"mode":"FAIL"}'
curl -s -XPOST "$H/hubs/DXB-HUB/route-plans" -H "Authorization: Bearer $OPS"   # plans OUT_FOR_DELIVERY shipments of the hub
curl -s "$H/ndr/report?from=$(date -u -d '-7 day' +%FT%TZ)&to=$(date -u +%FT%TZ)" -H "Authorization: Bearer $OPS"
```

The walkthrough above (except the NDR report call, whose shape is covered by `ScanIngestionIT`) was executed
against a fresh database seeded with `scripts/seed-dev.sql` (booking replay
returned the same id, the three scans were APPLIED, COD reconciliation reported 1 MATCHED, the scorecard showed the
delivery after the 1 s stats flush).

Other endpoints: `POST /shipments/{id}/cancel|reallocate|rto`, `POST /integrations/{source}/orders?originHub=`,
`POST /admin/merchants`, `POST /admin/carriers`, `PUT /admin/carriers/{code}/capacity`,
`POST /admin/webhooks/dead-letters/{eventId}/replay`, `/actuator/health`, `/actuator/prometheus` (ADMIN).

Errors are RFC-7807-style JSON with a stable `code` (`VALIDATION_FAILED`, `NOT_FOUND`, `FORBIDDEN`,
`IDEMPOTENCY_KEY_REUSED`, `DUPLICATE_ORDER`, `ALREADY_EXISTS`, `UNSERVICEABLE`, `INVALID_STATE_TRANSITION`, …).

## Extending

**Add a carrier (data only, simulated or own fleet integration):** `POST /admin/carriers`
```json
{"code":"QUICK","name":"Quick Couriers","kind":"THIRD_PARTY","integration":"SIMULATED_3PL",
 "zones":[{"zoneCode":"DXB-Z1","slaDays":2,"codSupported":true,"maxWeightGrams":30000,"currency":"AED",
           "extraPer500g":1.00,"codFeePercent":1.5,"codFeeMin":2.00,"defaultDailyCapacity":300,
           "slabs":[{"upToGrams":5000,"price":12.00}]}]}
```
**Add a carrier integration (new API):** implement `CarrierPort` (`integration()` returns the key stored in
`carrier.integration`; `book` idempotent on `bookingRef`; throw `CarrierRejectedException` for business rejections;
implement `lookup` for reconciliation) and annotate it `@Component`. `CarrierPortRegistry` picks it up.

**Add an allocation rule:** `@Component` implementing `AllocationRule` (score in [0,1], 1 = best) and add
`shipflow.allocation.weights.<key>`. A hard constraint is a `CandidateFilter` (`@Order` controls evaluation order).

**Add a risk rule:** `@Component` implementing `RiskRule` (`code`, `maxPoints`, `evaluate(RiskInput)` returning a
`RiskFactor` with a reason). Keep the sum of budgets at 100 so bands stay meaningful (`shipflow.risk.*-threshold`).

**Add an NDR policy / store adapter / routing heuristic:** `ReattemptPolicy`, `OrderAdapter`,
`ClusteringStrategy` / `RouteSequencer` – same pattern, see docs/ARCHITECTURE.md §2.
