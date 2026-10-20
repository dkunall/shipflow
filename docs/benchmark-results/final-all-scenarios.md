# ShipFlow benchmark run 2026-10-20T20:02:42Z

- CPU: 8 vCPU, Intel(R) Xeon(R) Processor
- MemTotal: 16386368 kB
- JVM: OpenJDK 64-Bit Server VM 21.0.12.1, max heap 2048 MB
- PostgreSQL: PostgreSQL 17.11 (Debian 17.11-0+deb13u1) on x86_64-pc-linux-gnu, compiled by gcc (Debian 14.2.0-19) 14.2.0, 64-bit (same host as the JVM)
- PG settings: {max_connections=100, shared_buffers=128MB, synchronous_commit=on, fsync=on, wal_level=replica}
- Hikari maximum-pool-size: 20; allocation reserve-last: true
- Warm-up 5 s, then 3 measured runs x 10 s per configuration; fresh data per run

## (1) Booking + allocation (idempotent create, risk, allocation with atomic capacity reserve, carrier booking, confirm)

Each op = `ShipmentBookingFacade.create` with a fresh Idempotency-Key: idempotency claim tx, create+allocate tx (shipment, history, outbox, capacity upsert, decision), simulated 3PL call outside any tx, confirm tx. 4 carriers x 1 zone => every booking contends on the same few `carrier_capacity` rows. 60% COD.

| scenario | threads | run | ok ops/s | p50 ms | p95 ms | p99 ms | max ms | avg pool waiters | avg lock waiters | deadlocks | errors | invariants |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| booking (3PL latency 0 ms) | 50 | 1 | 934 | 48.88 | 101.71 | 133.14 | 270.3 | 29.7 | 8.6 | 0 | 0 | {overbooked=0, alloc!=reserved=0, unconfirmed=0, raceRetries=0, dupConsignments=0} |
| booking (3PL latency 0 ms) | 50 | 2 | 1006 | 44.34 | 100.02 | 136.04 | 227.2 | 29.7 | 9.3 | 0 | 0 | {overbooked=0, alloc!=reserved=0, unconfirmed=0, raceRetries=0, dupConsignments=0} |
| booking (3PL latency 0 ms) | 50 | 3 | 1044 | 43.24 | 95.70 | 127.20 | 234.8 | 29.8 | 8.4 | 0 | 0 | {overbooked=0, alloc!=reserved=0, unconfirmed=0, raceRetries=0, dupConsignments=0} |
| **booking (3PL latency 0 ms) median** | 50 | - | **1006 | 44.34 | 100.02 | 133.14** | | | | | | | | | |
| booking (3PL latency 0 ms) | 100 | 1 | 1015 | 85.68 | 211.94 | 288.82 | 585.7 | 79.4 | 7.6 | 0 | 0 | {overbooked=0, alloc!=reserved=0, unconfirmed=0, raceRetries=0, dupConsignments=0} |
| booking (3PL latency 0 ms) | 100 | 2 | 1332 | 66.52 | 160.32 | 215.63 | 399.2 | 79.8 | 7.1 | 0 | 0 | {overbooked=0, alloc!=reserved=0, unconfirmed=0, raceRetries=0, dupConsignments=0} |
| booking (3PL latency 0 ms) | 100 | 3 | 986 | 87.78 | 221.28 | 300.13 | 503.5 | 79.3 | 8.4 | 0 | 0 | {overbooked=0, alloc!=reserved=0, unconfirmed=0, raceRetries=0, dupConsignments=0} |
| **booking (3PL latency 0 ms) median** | 100 | - | **1015 | 85.68 | 211.94 | 288.82** | | | | | | | | | |
| booking (3PL latency 0 ms) | 200 | 1 | 993 | 172.20 | 457.31 | 629.96 | 1205.5 | 178.9 | 7.8 | 0 | 0 | {overbooked=0, alloc!=reserved=0, unconfirmed=0, raceRetries=0, dupConsignments=0} |
| booking (3PL latency 0 ms) | 200 | 2 | 950 | 177.94 | 479.34 | 663.59 | 1164.4 | 178.9 | 7.1 | 0 | 0 | {overbooked=0, alloc!=reserved=0, unconfirmed=0, raceRetries=0, dupConsignments=0} |
| booking (3PL latency 0 ms) | 200 | 3 | 904 | 185.69 | 509.26 | 704.09 | 1176.3 | 179.8 | 8.7 | 0 | 0 | {overbooked=0, alloc!=reserved=0, unconfirmed=0, raceRetries=0, dupConsignments=0} |
| **booking (3PL latency 0 ms) median** | 200 | - | **950 | 177.94 | 479.34 | 663.59** | | | | | | | | | |
| booking (3PL latency 50 ms) | 50 | 1 | 805 | 59.02 | 79.03 | 102.59 | 182.4 | 1.0 | 1.7 | 0 | 0 | {overbooked=0, alloc!=reserved=0, unconfirmed=0, raceRetries=0, dupConsignments=0} |
| booking (3PL latency 50 ms) | 50 | 2 | 839 | 57.68 | 69.26 | 86.96 | 112.6 | 0.3 | 1.0 | 0 | 0 | {overbooked=0, alloc!=reserved=0, unconfirmed=0, raceRetries=0, dupConsignments=0} |
| booking (3PL latency 50 ms) | 50 | 3 | 798 | 59.98 | 77.76 | 94.31 | 130.2 | 0.6 | 1.1 | 0 | 0 | {overbooked=0, alloc!=reserved=0, unconfirmed=0, raceRetries=0, dupConsignments=0} |
| **booking (3PL latency 50 ms) median** | 50 | - | **805 | 59.02 | 77.76 | 94.31** | | | | | | | | | |
| booking (3PL latency 50 ms) | 100 | 1 | 898 | 103.45 | 171.25 | 216.26 | 384.3 | 34.9 | 8.5 | 0 | 0 | {overbooked=0, alloc!=reserved=0, unconfirmed=0, raceRetries=0, dupConsignments=0} |
| booking (3PL latency 50 ms) | 100 | 2 | 958 | 97.28 | 159.12 | 202.94 | 312.1 | 32.7 | 8.6 | 0 | 0 | {overbooked=0, alloc!=reserved=0, unconfirmed=0, raceRetries=0, dupConsignments=0} |
| booking (3PL latency 50 ms) | 100 | 3 | 1248 | 75.36 | 113.81 | 141.29 | 201.7 | 18.2 | 7.4 | 0 | 0 | {overbooked=0, alloc!=reserved=0, unconfirmed=0, raceRetries=0, dupConsignments=0} |
| **booking (3PL latency 50 ms) median** | 100 | - | **958 | 97.28 | 159.12 | 202.94** | | | | | | | | | |
| booking (3PL latency 50 ms) | 200 | 1 | 970 | 185.25 | 379.11 | 497.93 | 846.6 | 129.8 | 7.2 | 0 | 0 | {overbooked=0, alloc!=reserved=0, unconfirmed=0, raceRetries=0, dupConsignments=0} |
| booking (3PL latency 50 ms) | 200 | 2 | 1075 | 166.67 | 335.51 | 443.75 | 775.0 | 124.3 | 6.8 | 0 | 0 | {overbooked=0, alloc!=reserved=0, unconfirmed=0, raceRetries=0, dupConsignments=0} |
| booking (3PL latency 50 ms) | 200 | 3 | 985 | 180.29 | 378.13 | 489.92 | 1003.0 | 130.0 | 7.9 | 0 | 0 | {overbooked=0, alloc!=reserved=0, unconfirmed=0, raceRetries=0, dupConsignments=0} |
| **booking (3PL latency 50 ms) median** | 200 | - | **985 | 180.29 | 378.13 | 489.92** | | | | | | | | | |

Invariant columns: `overbooked` = capacity rows with reserved > capacity; `alloc!=reserved` = |sum(reserved) - RESERVED decisions|; `unconfirmed` = ALLOCATED shipments without AWB at end of run (in-flight at stop).

## (2) Scan ingestion (idempotent by carrier event id, out-of-order safe, optimistic locking)

Pre-created ALLOCATED shipments (one template booked through the real path, cloned with SQL for speed). Each thread walks its own shipments through 5 scans; ~10% of scans are re-sent duplicates. Each op = one `ScanIngestionService.ingest`. DELIVERED triggers NDR/scorecard/lane-lead-time listeners and an outbox row.

Statistics are write-behind: like the production scheduler, a background thread folds `delivery_outcome` into the scorecard/lane/risk counters every 1 s during the run (its DB work competes with the scans).

| scenario | threads | run | ok ops/s | p50 ms | p95 ms | p99 ms | max ms | avg pool waiters | avg lock waiters | deadlocks | errors | invariants |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| scan ingest | 50 | 1 | 5745 | 3.90 | 31.18 | 51.98 | 167.0 | 29.7 | 0.0 | 0 | 0 | {dupEffects=0, historyMismatch=0, delivered=10584, statsMismatch=0} |
| scan ingest | 50 | 2 | 6109 | 3.72 | 29.69 | 46.23 | 137.9 | 29.8 | 0.0 | 0 | 0 | {dupEffects=0, historyMismatch=0, delivered=11310, statsMismatch=0} |
| scan ingest | 50 | 3 | 5806 | 3.88 | 31.20 | 50.50 | 142.8 | 29.7 | 0.0 | 0 | 0 | {dupEffects=0, historyMismatch=0, delivered=10707, statsMismatch=0} |
| **scan ingest median** | 50 | - | **5806 | 3.88 | 31.18 | 50.50** | | | | | | | | | |
| scan ingest | 100 | 1 | 6426 | 3.55 | 69.35 | 110.10 | 251.9 | 79.5 | 0.0 | 0 | 0 | {dupEffects=0, historyMismatch=0, delivered=11891, statsMismatch=0} |
| scan ingest | 100 | 2 | 6382 | 3.54 | 69.65 | 110.46 | 242.6 | 79.1 | 0.0 | 0 | 0 | {dupEffects=0, historyMismatch=0, delivered=11799, statsMismatch=0} |
| scan ingest | 100 | 3 | 6005 | 3.83 | 73.86 | 114.44 | 317.2 | 79.5 | 0.0 | 0 | 0 | {dupEffects=0, historyMismatch=0, delivered=11040, statsMismatch=0} |
| **scan ingest median** | 100 | - | **6382 | 3.55 | 69.65 | 110.46** | | | | | | | | | |
| scan ingest | 200 | 1 | 6137 | 3.74 | 160.52 | 261.51 | 710.6 | 178.4 | 0.0 | 0 | 0 | {dupEffects=0, historyMismatch=0, delivered=11319, statsMismatch=0} |
| scan ingest | 200 | 2 | 6016 | 3.87 | 158.75 | 247.06 | 541.4 | 179.6 | 0.0 | 0 | 0 | {dupEffects=0, historyMismatch=0, delivered=11097, statsMismatch=0} |
| scan ingest | 200 | 3 | 6013 | 3.78 | 158.47 | 252.45 | 654.2 | 178.2 | 0.0 | 0 | 0 | {dupEffects=0, historyMismatch=0, delivered=11092, statsMismatch=0} |
| **scan ingest median** | 200 | - | **6016 | 3.78 | 158.75 | 252.45** | | | | | | | | | |

Invariant columns: `dupEffects` = scan events stored twice for one (carrier, event id) (must be 0); `historyMismatch` = applied non-duplicate scans without exactly one history row (must be 0); `statsMismatch` = |scorecard delivered + pending outcomes - DELIVERED shipments| after a final flush (must be 0).

## (3) Rider batching + routing vs naive baseline (pure, in-memory)

Seeded random stops in a ~35 x 40 km box around the hub, riders with capacity 25. Naive = creation order, fill rider by rider, visit in that order. Optimised = capacity-balanced sweep clustering + nearest neighbour + 2-opt. Distances are closed tours (hub -> stops -> hub), haversine km. Runtime = median of 3 timed repetitions after 3 warm-up repetitions; km are deterministic per seed (median over 5 seeds shown).

| stops | riders | naive km | NN only km | NN + 2-opt km | saving vs naive | optimised runtime ms (median) | naive runtime ms |
|---|---|---|---|---|---|---|---|
| 100 | 4 | 2093.8 | 431.6 | 374.8 | 82.1 % | 0.57 | 0.023 |
| 500 | 20 | 10140.3 | 1225.6 | 1062.6 | 89.5 % | 1.39 | 0.063 |
| 1000 | 40 | 20391.6 | 2060.1 | 1881.7 | 90.8 % | 2.68 | 0.093 |
| 2000 | 80 | 40363.4 | 3762.0 | 3544.6 | 91.2 % | 5.82 | 0.148 |

## Summary (medians)

| metric | value |
|---|---|
| booking (3PL latency 0 ms), 50 threads: ops/s | p50 | p95 | p99 ms | 1006 | 44.34 | 100.02 | 133.14 |
| booking (3PL latency 0 ms), 100 threads: ops/s | p50 | p95 | p99 ms | 1015 | 85.68 | 211.94 | 288.82 |
| booking (3PL latency 0 ms), 200 threads: ops/s | p50 | p95 | p99 ms | 950 | 177.94 | 479.34 | 663.59 |
| booking (3PL latency 50 ms), 50 threads: ops/s | p50 | p95 | p99 ms | 805 | 59.02 | 77.76 | 94.31 |
| booking (3PL latency 50 ms), 100 threads: ops/s | p50 | p95 | p99 ms | 958 | 97.28 | 159.12 | 202.94 |
| booking (3PL latency 50 ms), 200 threads: ops/s | p50 | p95 | p99 ms | 985 | 180.29 | 378.13 | 489.92 |
| scan ingest, 50 threads: ops/s | p50 | p95 | p99 ms | 5806 | 3.88 | 31.18 | 50.50 |
| scan ingest, 100 threads: ops/s | p50 | p95 | p99 ms | 6382 | 3.55 | 69.65 | 110.46 |
| scan ingest, 200 threads: ops/s | p50 | p95 | p99 ms | 6016 | 3.78 | 158.75 | 252.45 |
| routing 100 stops: saving vs naive | runtime ms | 82.1 % | 0.57 |
| routing 500 stops: saving vs naive | runtime ms | 89.5 % | 1.39 |
| routing 1000 stops: saving vs naive | runtime ms | 90.8 % | 2.68 |
| routing 2000 stops: saving vs naive | runtime ms | 91.2 % | 5.82 |
