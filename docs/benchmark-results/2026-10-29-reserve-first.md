# ShipFlow benchmark run 2026-10-29T18:16:36Z

- CPU: 8 vCPU, Intel(R) Xeon(R) Processor
- MemTotal: 16386368 kB
- JVM: OpenJDK 64-Bit Server VM 21.0.13, max heap 1024 MB
- PostgreSQL: PostgreSQL 17.11 on x86_64-pc-linux-musl, compiled by gcc (Alpine 15.2.0) 15.2.0, 64-bit (same host as the JVM)
- PG settings: {max_connections=100, shared_buffers=128MB, synchronous_commit=on, fsync=on, wal_level=replica}
- Hikari maximum-pool-size: 20; allocation reserve-last: false
- Warm-up 5 s, then 1 measured runs x 10 s per configuration; fresh data per run

## (1) Booking + allocation (idempotent create, risk, allocation with atomic capacity reserve, carrier booking, confirm)

Each op = `ShipmentBookingFacade.create` with a fresh Idempotency-Key: idempotency claim tx, create+allocate tx (shipment, history, outbox, capacity upsert, decision), simulated 3PL call outside any tx, confirm tx. 4 carriers x 1 zone => every booking contends on the same few `carrier_capacity` rows. 60% COD.

| scenario | threads | run | ok ops/s | p50 ms | p95 ms | p99 ms | max ms | avg pool waiters | avg lock waiters | deadlocks | errors | invariants |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| booking (3PL latency 0 ms) | 50 | 1 | 253 | 173.41 | 386.89 | 567.93 | 844.3 | 29.9 | 15.1 | 0 | 0 | {overbooked=0, alloc!=reserved=0, unconfirmed=0, raceRetries=0, dupConsignments=0} |
| **booking (3PL latency 0 ms) median** | 50 | - | **253 | 173.41 | 386.89 | 567.93** | | | | | | | | | |
| booking (3PL latency 0 ms) | 100 | 1 | 283 | 307.71 | 680.91 | 899.31 | 1180.9 | 80.0 | 15.9 | 0 | 0 | {overbooked=0, alloc!=reserved=0, unconfirmed=0, raceRetries=0, dupConsignments=0} |
| **booking (3PL latency 0 ms) median** | 100 | - | **283 | 307.71 | 680.91 | 899.31** | | | | | | | | | |
| booking (3PL latency 50 ms) | 50 | 1 | 215 | 201.30 | 451.15 | 611.98 | 1004.9 | 19.0 | 15.6 | 0 | 0 | {overbooked=0, alloc!=reserved=0, unconfirmed=0, raceRetries=0, dupConsignments=0} |
| **booking (3PL latency 50 ms) median** | 50 | - | **215 | 201.30 | 451.15 | 611.98** | | | | | | | | | |
| booking (3PL latency 50 ms) | 100 | 1 | 183 | 481.66 | 988.15 | 1245.92 | 1678.5 | 70.6 | 14.5 | 0 | 0 | {overbooked=0, alloc!=reserved=0, unconfirmed=0, raceRetries=0, dupConsignments=0} |
| **booking (3PL latency 50 ms) median** | 100 | - | **183 | 481.66 | 988.15 | 1245.92** | | | | | | | | | |

Invariant columns: `overbooked` = capacity rows with reserved > capacity; `alloc!=reserved` = |sum(reserved) - RESERVED decisions|; `unconfirmed` = ALLOCATED shipments without AWB at end of run (in-flight at stop).

## Summary (medians)

| metric | value |
|---|---|
| booking (3PL latency 0 ms), 50 threads: ops/s | p50 | p95 | p99 ms | 253 | 173.41 | 386.89 | 567.93 |
| booking (3PL latency 0 ms), 100 threads: ops/s | p50 | p95 | p99 ms | 283 | 307.71 | 680.91 | 899.31 |
| booking (3PL latency 50 ms), 50 threads: ops/s | p50 | p95 | p99 ms | 215 | 201.30 | 451.15 | 611.98 |
| booking (3PL latency 50 ms), 100 threads: ops/s | p50 | p95 | p99 ms | 183 | 481.66 | 988.15 | 1245.92 |
