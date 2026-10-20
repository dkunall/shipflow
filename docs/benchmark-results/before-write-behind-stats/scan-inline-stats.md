# ShipFlow benchmark run 2026-10-20T20:14:47Z

- CPU: 8 vCPU, Intel(R) Xeon(R) Processor
- MemTotal: 16386368 kB
- JVM: OpenJDK 64-Bit Server VM 21.0.12.1, max heap 2048 MB
- PostgreSQL: PostgreSQL 17.11 (Debian 17.11-0+deb13u1) on x86_64-pc-linux-gnu, compiled by gcc (Debian 14.2.0-19) 14.2.0, 64-bit (same host as the JVM)
- PG settings: {max_connections=100, shared_buffers=128MB, synchronous_commit=on, fsync=on, wal_level=replica}
- Hikari maximum-pool-size: 20; allocation reserve-last: true
- Warm-up 5 s, then 3 measured runs x 10 s per configuration; fresh data per run

## (2) Scan ingestion (idempotent by carrier event id, out-of-order safe, optimistic locking)

Pre-created ALLOCATED shipments (one template booked through the real path, cloned with SQL for speed). Each thread walks its own shipments through 5 scans; ~10% of scans are re-sent duplicates. Each op = one `ScanIngestionService.ingest`. DELIVERED triggers NDR/scorecard/lane-lead-time listeners and an outbox row.

| scenario | threads | run | ok ops/s | p50 ms | p95 ms | p99 ms | max ms | avg pool waiters | avg lock waiters | deadlocks | errors | invariants |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| scan ingest | 50 | 1 | 3595 | 8.64 | 48.33 | 89.25 | 295.2 | 29.6 | 12.9 | 0 | 0 | {dupEffects=0, historyMismatch=0, delivered=6616} |
| scan ingest | 50 | 2 | 3944 | 6.97 | 46.37 | 81.88 | 209.0 | 29.6 | 13.4 | 0 | 0 | {dupEffects=0, historyMismatch=0, delivered=7242} |
| scan ingest | 50 | 3 | 4203 | 6.32 | 44.46 | 75.54 | 213.3 | 29.9 | 12.5 | 0 | 0 | {dupEffects=0, historyMismatch=0, delivered=7733} |
| **scan ingest median** | 50 | - | **3944 | 6.97 | 46.37 | 81.88** | | | | | | | | | |
| scan ingest | 100 | 1 | 3810 | 15.00 | 97.14 | 151.41 | 379.4 | 79.7 | 11.6 | 0 | 0 | {dupEffects=0, historyMismatch=0, delivered=7053} |
| scan ingest | 100 | 2 | 3583 | 7.84 | 108.34 | 168.96 | 407.0 | 79.8 | 13.1 | 0 | 0 | {dupEffects=0, historyMismatch=0, delivered=6587} |
| scan ingest | 100 | 3 | 3723 | 7.21 | 102.58 | 169.33 | 416.9 | 80.0 | 13.4 | 0 | 0 | {dupEffects=0, historyMismatch=0, delivered=6830} |
| **scan ingest median** | 100 | - | **3723 | 7.84 | 102.58 | 168.96** | | | | | | | | | |
| scan ingest | 200 | 1 | 3627 | 1.99 | 266.57 | 485.27 | 1801.4 | 178.7 | 12.9 | 0 | 0 | {dupEffects=0, historyMismatch=0, delivered=6650} |
| scan ingest | 200 | 2 | 3621 | 1.91 | 275.61 | 528.97 | 2636.3 | 178.3 | 12.9 | 0 | 0 | {dupEffects=0, historyMismatch=0, delivered=6630} |
| scan ingest | 200 | 3 | 3669 | 1.81 | 270.16 | 514.76 | 1874.3 | 179.5 | 13.6 | 0 | 0 | {dupEffects=0, historyMismatch=0, delivered=6742} |
| **scan ingest median** | 200 | - | **3627 | 1.91 | 270.16 | 514.76** | | | | | | | | | |

Invariant columns: `dupEffects` = scan events stored twice for one (carrier, event id) (must be 0); `historyMismatch` = applied non-duplicate scans without exactly one history row (must be 0).

## Summary (medians)

| metric | value |
|---|---|
| scan ingest, 50 threads: ops/s | p50 | p95 | p99 ms | 3944 | 6.97 | 46.37 | 81.88 |
| scan ingest, 100 threads: ops/s | p50 | p95 | p99 ms | 3723 | 7.84 | 102.58 | 168.96 |
| scan ingest, 200 threads: ops/s | p50 | p95 | p99 ms | 3627 | 1.91 | 270.16 | 514.76 |
