# ShipFlow benchmark run 2026-10-20T20:11:00Z

- CPU: 8 vCPU, Intel(R) Xeon(R) Processor
- MemTotal: 16386368 kB
- JVM: OpenJDK 64-Bit Server VM 21.0.12.1, max heap 2048 MB
- PostgreSQL: PostgreSQL 17.11 (Debian 17.11-0+deb13u1) on x86_64-pc-linux-gnu, compiled by gcc (Debian 14.2.0-19) 14.2.0, 64-bit (same host as the JVM)
- PG settings: {max_connections=100, shared_buffers=128MB, synchronous_commit=on, fsync=on, wal_level=replica}
- Hikari maximum-pool-size: 20; allocation reserve-last: false
- Warm-up 5 s, then 3 measured runs x 10 s per configuration; fresh data per run

## (1) Booking + allocation (idempotent create, risk, allocation with atomic capacity reserve, carrier booking, confirm)

Each op = `ShipmentBookingFacade.create` with a fresh Idempotency-Key: idempotency claim tx, create+allocate tx (shipment, history, outbox, capacity upsert, decision), simulated 3PL call outside any tx, confirm tx. 4 carriers x 1 zone => every booking contends on the same few `carrier_capacity` rows. 60% COD.

| scenario | threads | run | ok ops/s | p50 ms | p95 ms | p99 ms | max ms | avg pool waiters | avg lock waiters | deadlocks | errors | invariants |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| booking (3PL latency 0 ms) | 50 | 1 | 519 | 84.66 | 189.49 | 262.13 | 493.7 | 29.9 | 15.6 | 0 | 0 | {overbooked=0, alloc!=reserved=0, unconfirmed=0, raceRetries=0, dupConsignments=0} |
| booking (3PL latency 0 ms) | 50 | 2 | 507 | 87.26 | 198.95 | 270.93 | 492.0 | 30.0 | 15.5 | 0 | 0 | {overbooked=0, alloc!=reserved=0, unconfirmed=0, raceRetries=0, dupConsignments=0} |
| booking (3PL latency 0 ms) | 50 | 3 | 571 | 77.24 | 176.56 | 238.68 | 480.0 | 30.0 | 15.7 | 0 | 0 | {overbooked=0, alloc!=reserved=0, unconfirmed=0, raceRetries=0, dupConsignments=0} |
| **booking (3PL latency 0 ms) median** | 50 | - | **519 | 84.66 | 189.49 | 262.13** | | | | | | | | | |
| booking (3PL latency 0 ms) | 100 | 1 | 555 | 159.96 | 341.50 | 438.48 | 793.5 | 79.9 | 15.6 | 0 | 0 | {overbooked=0, alloc!=reserved=0, unconfirmed=0, raceRetries=0, dupConsignments=0} |
| booking (3PL latency 0 ms) | 100 | 2 | 556 | 160.10 | 341.74 | 458.15 | 823.4 | 79.5 | 15.5 | 0 | 0 | {overbooked=0, alloc!=reserved=0, unconfirmed=0, raceRetries=0, dupConsignments=0} |
| booking (3PL latency 0 ms) | 100 | 3 | 537 | 167.09 | 348.07 | 462.50 | 909.2 | 79.9 | 14.9 | 0 | 0 | {overbooked=0, alloc!=reserved=0, unconfirmed=0, raceRetries=0, dupConsignments=0} |
| **booking (3PL latency 0 ms) median** | 100 | - | **555 | 160.10 | 341.74 | 458.15** | | | | | | | | | |
| booking (3PL latency 0 ms) | 200 | 1 | 576 | 303.66 | 663.62 | 887.48 | 1343.6 | 178.6 | 15.8 | 0 | 0 | {overbooked=0, alloc!=reserved=0, unconfirmed=0, raceRetries=0, dupConsignments=0} |
| booking (3PL latency 0 ms) | 200 | 2 | 573 | 303.14 | 681.80 | 900.54 | 1574.7 | 178.2 | 15.6 | 0 | 0 | {overbooked=0, alloc!=reserved=0, unconfirmed=0, raceRetries=0, dupConsignments=0} |
| booking (3PL latency 0 ms) | 200 | 3 | 562 | 308.01 | 685.17 | 926.33 | 1385.8 | 178.3 | 14.9 | 0 | 0 | {overbooked=0, alloc!=reserved=0, unconfirmed=0, raceRetries=0, dupConsignments=0} |
| **booking (3PL latency 0 ms) median** | 200 | - | **573 | 303.66 | 681.80 | 900.54** | | | | | | | | | |
| booking (3PL latency 50 ms) | 50 | 1 | 599 | 72.03 | 148.82 | 207.30 | 316.2 | 2.3 | 13.3 | 0 | 0 | {overbooked=0, alloc!=reserved=0, unconfirmed=0, raceRetries=0, dupConsignments=0} |
| booking (3PL latency 50 ms) | 50 | 2 | 607 | 71.85 | 142.68 | 197.13 | 419.5 | 2.0 | 12.7 | 0 | 0 | {overbooked=0, alloc!=reserved=0, unconfirmed=0, raceRetries=0, dupConsignments=0} |
| booking (3PL latency 50 ms) | 50 | 3 | 595 | 72.79 | 145.15 | 202.67 | 330.2 | 2.5 | 13.5 | 0 | 0 | {overbooked=0, alloc!=reserved=0, unconfirmed=0, raceRetries=0, dupConsignments=0} |
| **booking (3PL latency 50 ms) median** | 50 | - | **599 | 72.03 | 145.15 | 202.67** | | | | | | | | | |
| booking (3PL latency 50 ms) | 100 | 1 | 663 | 139.32 | 244.35 | 315.11 | 509.3 | 46.6 | 15.2 | 0 | 0 | {overbooked=0, alloc!=reserved=0, unconfirmed=0, raceRetries=0, dupConsignments=0} |
| booking (3PL latency 50 ms) | 100 | 2 | 595 | 155.46 | 276.84 | 335.61 | 523.2 | 49.7 | 15.9 | 0 | 0 | {overbooked=0, alloc!=reserved=0, unconfirmed=0, raceRetries=0, dupConsignments=0} |
| booking (3PL latency 50 ms) | 100 | 3 | 600 | 154.54 | 267.66 | 332.43 | 506.6 | 50.3 | 15.6 | 0 | 0 | {overbooked=0, alloc!=reserved=0, unconfirmed=0, raceRetries=0, dupConsignments=0} |
| **booking (3PL latency 50 ms) median** | 100 | - | **600 | 154.54 | 267.66 | 332.43** | | | | | | | | | |
| booking (3PL latency 50 ms) | 200 | 1 | 587 | 301.57 | 606.96 | 784.08 | 1231.3 | 149.5 | 15.5 | 0 | 0 | {overbooked=0, alloc!=reserved=0, unconfirmed=0, raceRetries=0, dupConsignments=0} |
| booking (3PL latency 50 ms) | 200 | 2 | 591 | 303.01 | 591.31 | 770.04 | 1563.0 | 148.9 | 16.1 | 0 | 0 | {overbooked=0, alloc!=reserved=0, unconfirmed=0, raceRetries=0, dupConsignments=0} |
| booking (3PL latency 50 ms) | 200 | 3 | 559 | 316.13 | 641.56 | 837.69 | 1293.9 | 150.9 | 15.3 | 0 | 0 | {overbooked=0, alloc!=reserved=0, unconfirmed=0, raceRetries=0, dupConsignments=0} |
| **booking (3PL latency 50 ms) median** | 200 | - | **587 | 303.01 | 606.96 | 784.08** | | | | | | | | | |

Invariant columns: `overbooked` = capacity rows with reserved > capacity; `alloc!=reserved` = |sum(reserved) - RESERVED decisions|; `unconfirmed` = ALLOCATED shipments without AWB at end of run (in-flight at stop).

## Summary (medians)

| metric | value |
|---|---|
| booking (3PL latency 0 ms), 50 threads: ops/s | p50 | p95 | p99 ms | 519 | 84.66 | 189.49 | 262.13 |
| booking (3PL latency 0 ms), 100 threads: ops/s | p50 | p95 | p99 ms | 555 | 160.10 | 341.74 | 458.15 |
| booking (3PL latency 0 ms), 200 threads: ops/s | p50 | p95 | p99 ms | 573 | 303.66 | 681.80 | 900.54 |
| booking (3PL latency 50 ms), 50 threads: ops/s | p50 | p95 | p99 ms | 599 | 72.03 | 145.15 | 202.67 |
| booking (3PL latency 50 ms), 100 threads: ops/s | p50 | p95 | p99 ms | 600 | 154.54 | 267.66 | 332.43 |
| booking (3PL latency 50 ms), 200 threads: ops/s | p50 | p95 | p99 ms | 587 | 303.01 | 606.96 | 784.08 |
