# ShipFlow benchmark run 2026-10-29T18:07:55Z

- CPU: 8 vCPU, Intel(R) Xeon(R) Processor
- MemTotal: 16386368 kB
- JVM: OpenJDK 64-Bit Server VM 21.0.13, max heap 1024 MB
- PostgreSQL: PostgreSQL 17.11 on x86_64-pc-linux-musl, compiled by gcc (Alpine 15.2.0) 15.2.0, 64-bit (same host as the JVM)
- PG settings: {max_connections=100, shared_buffers=128MB, synchronous_commit=on, fsync=on, wal_level=replica}
- Hikari maximum-pool-size: 20; allocation reserve-last: true
- Warm-up 5 s, then 1 measured runs x 10 s per configuration; fresh data per run

## (1) Booking + allocation (idempotent create, risk, allocation with atomic capacity reserve, carrier booking, confirm)

Each op = `ShipmentBookingFacade.create` with a fresh Idempotency-Key: idempotency claim tx, create+allocate tx (shipment, history, outbox, capacity upsert, decision), simulated 3PL call outside any tx, confirm tx. 4 carriers x 1 zone => every booking contends on the same few `carrier_capacity` rows. 60% COD.

| scenario | threads | run | ok ops/s | p50 ms | p95 ms | p99 ms | max ms | avg pool waiters | avg lock waiters | deadlocks | errors | invariants |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| booking (3PL latency 0 ms) | 50 | 1 | 311 | 133.10 | 339.87 | 643.79 | 1288.2 | 29.8 | 7.6 | 0 | 0 | {overbooked=0, alloc!=reserved=0, unconfirmed=0, raceRetries=0, dupConsignments=0} |
| **booking (3PL latency 0 ms) median** | 50 | - | **311 | 133.10 | 339.87 | 643.79** | | | | | | | | | |
| booking (3PL latency 0 ms) | 100 | 1 | 432 | 203.40 | 483.61 | 648.94 | 879.9 | 79.9 | 7.3 | 0 | 0 | {overbooked=0, alloc!=reserved=0, unconfirmed=0, raceRetries=0, dupConsignments=0} |
| **booking (3PL latency 0 ms) median** | 100 | - | **432 | 203.40 | 483.61 | 648.94** | | | | | | | | | |
| booking (3PL latency 50 ms) | 50 | 1 | 385 | 119.24 | 208.84 | 254.82 | 350.5 | 11.1 | 6.6 | 0 | 0 | {overbooked=0, alloc!=reserved=0, unconfirmed=0, raceRetries=0, dupConsignments=0} |
| **booking (3PL latency 50 ms) median** | 50 | - | **385 | 119.24 | 208.84 | 254.82** | | | | | | | | | |
| booking (3PL latency 50 ms) | 100 | 1 | 361 | 250.37 | 487.16 | 645.35 | 1177.9 | 61.5 | 6.6 | 0 | 0 | {overbooked=0, alloc!=reserved=0, unconfirmed=0, raceRetries=0, dupConsignments=0} |
| **booking (3PL latency 50 ms) median** | 100 | - | **361 | 250.37 | 487.16 | 645.35** | | | | | | | | | |

Invariant columns: `overbooked` = capacity rows with reserved > capacity; `alloc!=reserved` = |sum(reserved) - RESERVED decisions|; `unconfirmed` = ALLOCATED shipments without AWB at end of run (in-flight at stop).

## (2) Scan ingestion (idempotent by carrier event id, out-of-order safe, optimistic locking)

Pre-created ALLOCATED shipments (one template booked through the real path, cloned with SQL for speed). Each thread walks its own shipments through 5 scans; ~10% of scans are re-sent duplicates. Each op = one `ScanIngestionService.ingest`. DELIVERED records the NDR attempt, appends a delivery_outcome row (write-behind stats) and an outbox row.

Statistics are write-behind: like the production scheduler, a background thread folds `delivery_outcome` into the scorecard/lane/risk counters every 1 s during the run (its DB work competes with the scans).

| scenario | threads | run | ok ops/s | p50 ms | p95 ms | p99 ms | max ms | avg pool waiters | avg lock waiters | deadlocks | errors | invariants |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| scan ingest | 50 | 1 | 1499 | 17.98 | 100.39 | 160.75 | 380.1 | 29.9 | 0.0 | 0 | 0 | {dupEffects=0, historyMismatch=0, delivered=2759, statsMismatch=0} |
| **scan ingest median** | 50 | - | **1499 | 17.98 | 100.39 | 160.75** | | | | | | | | | |
| scan ingest | 100 | 1 | 1887 | 16.61 | 188.14 | 296.85 | 623.9 | 79.5 | 0.0 | 0 | 0 | {dupEffects=0, historyMismatch=0, delivered=3478, statsMismatch=0} |
| **scan ingest median** | 100 | - | **1887 | 16.61 | 188.14 | 296.85** | | | | | | | | | |

Invariant columns: `dupEffects` = scan events stored twice for one (carrier, event id) (must be 0); `historyMismatch` = applied non-duplicate scans without exactly one history row (must be 0); `statsMismatch` = |scorecard delivered + pending outcomes - DELIVERED shipments| after a final flush (must be 0).

## (3) Rider batching + routing vs naive baseline (pure, in-memory)

Seeded random stops in a ~35 x 40 km box around the hub, riders with capacity 25. Naive = creation order, fill rider by rider, visit in that order. Optimised = capacity-balanced sweep clustering + nearest neighbour + 2-opt. Distances are closed tours (hub -> stops -> hub), haversine km. Runtime = median of 1 timed repetitions after 3 warm-up repetitions; km are deterministic per seed (median over 5 seeds shown).

| stops | riders | naive km | NN only km | NN + 2-opt km | saving vs naive | optimised runtime ms (median) | naive runtime ms |
|---|---|---|---|---|---|---|---|
| 100 | 4 | 2093.8 | 431.6 | 374.8 | 82.1 % | 0.61 | 0.023 |
| 500 | 20 | 10140.3 | 1225.6 | 1062.6 | 89.5 % | 1.19 | 0.045 |
| 1000 | 40 | 20391.6 | 2060.1 | 1881.7 | 90.8 % | 3.38 | 0.129 |
| 2000 | 80 | 40363.4 | 3762.0 | 3544.6 | 91.2 % | 5.81 | 0.171 |

## Summary (medians)

| metric | value |
|---|---|
| booking (3PL latency 0 ms), 50 threads: ops/s | p50 | p95 | p99 ms | 311 | 133.10 | 339.87 | 643.79 |
| booking (3PL latency 0 ms), 100 threads: ops/s | p50 | p95 | p99 ms | 432 | 203.40 | 483.61 | 648.94 |
| booking (3PL latency 50 ms), 50 threads: ops/s | p50 | p95 | p99 ms | 385 | 119.24 | 208.84 | 254.82 |
| booking (3PL latency 50 ms), 100 threads: ops/s | p50 | p95 | p99 ms | 361 | 250.37 | 487.16 | 645.35 |
| scan ingest, 50 threads: ops/s | p50 | p95 | p99 ms | 1499 | 17.98 | 100.39 | 160.75 |
| scan ingest, 100 threads: ops/s | p50 | p95 | p99 ms | 1887 | 16.61 | 188.14 | 296.85 |
| routing 100 stops: saving vs naive | runtime ms | 82.1 % | 0.61 |
| routing 500 stops: saving vs naive | runtime ms | 89.5 % | 1.19 |
| routing 1000 stops: saving vs naive | runtime ms | 90.8 % | 3.38 |
| routing 2000 stops: saving vs naive | runtime ms | 91.2 % | 5.81 |
