# ShipFlow – Performance

All numbers below were produced by `scripts/run-benchmark.sh` on the machine described here and are copied from the
raw result files in [`docs/benchmark-results/`](benchmark-results/). Nothing is extrapolated.

## 1. Environment

| item | value |
|---|---|
| host | VM, 8 vCPU (Intel Xeon), 16 GB RAM |
| app + DB | **same host** (JVM and PostgreSQL compete for the same 8 vCPUs and disk) |
| JVM | OpenJDK 21.0.12, max heap 2 GB, default GC |
| PostgreSQL | 17.11, **untuned defaults**: `shared_buffers=128MB`, `synchronous_commit=on`, `fsync=on`, `max_connections=100` |
| connection pool | HikariCP `maximum-pool-size = 20` |
| network | loopback; no TLS |

## 2. Method

- `ShipFlowBenchmark` boots the full Spring context (Flyway, JPA, security, metrics) against database
  `shipflow_bench` and calls the application services directly (`ShipmentBookingFacade.create`,
  `ScanIngestionService.ingest`) – i.e. everything except HTTP/JSON (de)serialisation and the servlet stack.
- Closed loop: N threads issue requests back-to-back. Latency per op = `System.nanoTime()` around the call;
  p50/p95/p99 are exact percentiles over all ops of a run (no histogram bucketing). Closed-loop latency includes
  queueing for a pool connection and is subject to coordinated omission – read the tail numbers as "under saturation".
- Per configuration: **5 s warm-up, then 3 measured runs × 10 s**, fresh data per run (tables truncated, capacity reset).
  Tables report every run plus the **median** run (median of ops/s; latency columns of the same median metric).
- During each run a sampler records every 100 ms: Hikari threads waiting for a connection ("pool waiters") and
  backends waiting on a heavyweight lock in `pg_stat_activity` ("lock waiters").
- Every run ends with **correctness invariants** checked in SQL; all were 0 in every run reported here:
  - booking: `overbooked` (capacity rows with reserved > capacity), `alloc!=reserved`, `unconfirmed`,
    `dupConsignments` (two confirmed bookings for one shipment), `raceRetries` (reserve-last retries);
  - scan: `dupEffects` (an event applied twice), `historyMismatch` (applied scans without exactly one history row),
    `statsMismatch` (scorecard `delivered` ≠ DELIVERED shipments after the final stats flush).
- Workloads:
  - **booking**: 4 carriers × 1 zone (own fleet deactivated so every booking goes to a 3PL), 60 % COD; one op =
    idempotency claim tx → create + risk + EDD + allocate + capacity reservation tx → simulated 3PL call (0 ms or
    50 ms) outside any tx → booking-confirm tx. Deliberately hostile: every booking contends on the same 4
    `carrier_capacity` rows.
  - **scan**: 120 000 ALLOCATED shipments per run (cloned with SQL from one shipment booked through the real path);
    each thread walks its own shipments PICKED_UP → IN_TRANSIT → AT_HUB → OUT_FOR_DELIVERY → DELIVERED, ~10 % of
    scans are re-sent duplicates (must return DUPLICATE). All deliveries are on **one lane** (same carrier/hub/zone/day)
    – the worst case for statistics counters.
  - **routing**: pure in-memory algorithm on seeded random stops in a ~35 × 40 km box, riders of capacity 25,
    median of 5 seeds; runtime = median of 3 timed repetitions after 3 warm-ups.

Reproduce:
```bash
scripts/run-benchmark.sh                                                       # final numbers (§3)
scripts/run-benchmark.sh -Dbench.scenarios=booking -Dbench.reserveLast=false   # §4.1 "before"
# §4.2 "before" = same command with -Dbench.scenarios=scan on commit 641a812 (before write-behind stats)
```

## 3. Results (final code) – `benchmark-results/final-all-scenarios.md`

### 3.1 Booking (end-to-end create + allocate + carrier booking + confirm)

| 3PL latency | threads | ops/s (median run) | p50 ms | p95 ms | p99 ms | ops/s range (3 runs) | avg pool waiters | avg lock waiters |
|---|---|---|---|---|---|---|---|---|
| 0 ms | 50 | **1006** | 44.3 | 100.0 | 133.1 | 934 – 1044 | 29.7 | 8.4 – 9.3 |
| 0 ms | 100 | **1015** | 85.7 | 211.9 | 288.8 | 986 – 1332 | 79.4 | 7.1 – 8.4 |
| 0 ms | 200 | **950** | 177.9 | 479.3 | 663.6 | 904 – 993 | 179 | 7.1 – 8.7 |
| 50 ms | 50 | **805** | 59.0 | 77.8 | 94.3 | 798 – 839 | 0.3 – 1.0 | 1.0 – 1.7 |
| 50 ms | 100 | **958** | 97.3 | 159.1 | 202.9 | 898 – 1248 | 32.7 | 7.4 – 8.6 |
| 50 ms | 200 | **985** | 180.3 | 378.1 | 489.9 | 970 – 1075 | 130 | 6.8 – 7.9 |

Errors 0, deadlocks 0, all invariants 0, `raceRetries` 0 in every run.

### 3.2 Scan ingestion

| threads | ops/s (median run) | p50 ms | p95 ms | p99 ms | ops/s range | avg pool waiters | avg lock waiters |
|---|---|---|---|---|---|---|---|
| 50 | **5806** | 3.88 | 31.2 | 50.5 | 5745 – 6109 | 29.7 | 0.0 |
| 100 | **6382** | 3.55 | 69.7 | 110.5 | 6005 – 6426 | 79.1 | 0.0 |
| 200 | **6016** | 3.78 | 158.8 | 252.5 | 6013 – 6137 | 180 | 0.0 |

Each op is one scan (≈ 10 % duplicates). ~11 000 shipments reached DELIVERED per 10 s run; statistics were folded
by the background aggregator during the run and `statsMismatch` was 0.

### 3.3 Routing (sweep clustering + nearest neighbour + 2-opt)

| stops | riders | naive km | NN only km | NN + 2-opt km | saving vs naive | **2-opt gain over NN** | runtime ms |
|---|---|---|---|---|---|---|---|
| 100 | 4 | 2093.8 | 431.6 | 374.8 | 82.1 % | **13.2 %** | 0.57 |
| 500 | 20 | 10140.3 | 1225.6 | 1062.6 | 89.5 % | **13.3 %** | 1.39 |
| 1000 | 40 | 20391.6 | 2060.1 | 1881.7 | 90.8 % | **8.7 %** | 2.68 |
| 2000 | 80 | 40363.4 | 3762.0 | 3544.6 | 91.2 % | **5.8 %** | 5.82 |

**Honest reading:** the 82–91 % "saving vs naive" is against a strawman – the naive baseline visits stops in
creation order with riders filled sequentially, so every rider criss-crosses the whole 35 × 40 km box. Most of the
gain comes from *any* geographic clustering. The algorithmic contribution that would survive comparison with a
sensible dispatcher is the **2-opt improvement over plain nearest neighbour: 5.8–13.3 %**, at < 6 ms for 2000 stops.
No comparison against an optimal solver (OR-Tools/LKH) was made, so the optimality gap is unknown.

## 4. Optimisations, measured before/after

Both optimisations were found by profiling the benchmark itself (`pg_stat_activity` wait events during a run),
not guessed.

### 4.1 Reserve-last optimistic capacity reservation (ADR-003)

Finding: with reserve-first, the capacity UPSERT is the first statement of the booking transaction, so the hot
`carrier_capacity` row stays row-locked for the whole transaction (shipment insert, history, decision, outbox,
idempotency completion, commit). `pg_stat_activity` showed most backends in `Lock:transactionid` on
`INSERT INTO carrier_capacity`. Fix: write everything, flush, reserve as the **last** statement; on a lost race
retry the whole transaction (final attempt falls back to reserve-first).

Before = `before-reserve-first/booking-reserve-first.md` (same code, `-Dbench.reserveLast=false`); after = §3.1.

| 3PL latency | threads | before ops/s | after ops/s | Δ | before p99 ms | after p99 ms | before → after avg lock waiters |
|---|---|---|---|---|---|---|---|
| 0 ms | 50 | 519 | 1006 | **+94 %** | 262.1 | 133.1 | 15.6 → 9.3 |
| 0 ms | 100 | 555 | 1015 | **+83 %** | 458.2 | 288.8 | 15.6 → 7.6 |
| 0 ms | 200 | 573 | 950 | **+66 %** | 900.5 | 663.6 | 15.6 → 7.1 |
| 50 ms | 50 | 599 | 805 | **+34 %** | 202.7 | 94.3 | 13.3 → 1.7 |
| 50 ms | 100 | 600 | 958 | **+60 %** | 332.4 | 202.9 | 15.6 → 8.6 |
| 50 ms | 200 | 587 | 985 | **+68 %** | 784.1 | 489.9 | 15.5 → 7.9 |

(lock waiters: median-run value, before → after.) No race retries were needed in any run (capacity never ran out
mid-run), so the retry path's cost is not represented here; it is exercised by `ConcurrencyIT` (200 concurrent
bookings against 100 units of capacity ⇒ exactly 100 ALLOCATED, 100 CREATED).

### 4.2 Write-behind statistics (ADR-011)

Finding: after the first fix, scans were the bottleneck at ~12–13 average lock waiters; `pg_stat_activity` showed
them queued on `INSERT INTO lane_lead_time … ON CONFLICT DO UPDATE` (and the scorecard row): every DELIVERED scan of a
lane incremented the same counter rows inside the scan transaction. Fix: append a `delivery_outcome` row in the scan
transaction (no shared row), fold batches into the counters every 1 s (`StatsAggregator`, claim + apply in one tx).

Before = `before-write-behind-stats/scan-inline-stats.md` (commit 641a812, identical harness minus the flusher);
after = §3.2 (includes the aggregator running concurrently).

| threads | before ops/s | after ops/s | Δ | before p50 / p99 ms | after p50 / p99 ms | before → after avg lock waiters |
|---|---|---|---|---|---|---|
| 50 | 3944 | 5806 | **+47 %** | 6.97 / 81.9 | 3.88 / 50.5 | 13.4 → 0.0 |
| 100 | 3723 | 6382 | **+71 %** | 7.84 / 169.0 | 3.55 / 110.5 | 13.4 → 0.0 |
| 200 | 3627 | 6016 | **+66 %** | 1.91 / 514.8 | 3.78 / 252.5 | 12.9 → 0.0 |

Cost of the trade-off: scorecards, lane lead-time histograms and risk stats are up to ~1 s stale. This workload is
the worst case (one lane); with many lanes the inline version would contend less and the gain would be smaller.

## 5. Interpretation – where the time goes now

- **Booking saturates at ~1 000 ops/s from 50 threads up; more threads only add latency.** With 20 connections,
  "pool waiters" ≈ threads − 20, and p50 grows linearly with threads (44 → 86 → 178 ms) – Little's law for a
  saturated queue (200 threads / ~1 000 ops/s ≈ 200 ms). Throughput run-to-run spread is notable (e.g. 986–1332 at
  100 threads) on a shared VM, which is why medians of 3 runs are reported.
- After reserve-last, the top wait events during booking are `LWLock:WALWrite` and `IO:WalSync` on `COMMIT`
  (fsync-bound, app and DB on one disk), with residual bursts of `Lock:transactionid` on the capacity row
  (avg 7–9 waiters). A booking currently costs **4 commits** (idempotency claim, create+allocate, booking claim,
  confirm) – the next lever would be merging the booking-claim into the create transaction, or sharding the capacity
  row; neither was implemented. `synchronous_commit=off` would trade durability and was deliberately not used.
- **3PL latency does not hold connections.** At 50 threads with a 50 ms carrier call, throughput (805/s) is close to
  threads / p50 = 50 / 0.059 s ≈ 850/s and pool waiters stay ≤ 1 (vs ~30 at 0 ms latency): the carrier call happens outside
  any transaction (ADR-005). With reserve-first the same configuration did 599/s.
- **Scans** run at ~6 000/s with p50 < 4 ms and zero lock waiters; the remaining limit is pool size / commit rate
  (pool waiters ≈ threads − 20). Tail latency at 200 threads is queueing, not locking.
- **Redis is not justified** by any measurement: every bottleneck observed was row-lock contention or WAL fsync in
  PostgreSQL, which a cache does not remove (ADR-012).

## 6. Not measured / caveats

- No HTTP-level load test (JSON, servlet, security filter chain excluded); expect lower numbers through the API.
- No multi-instance test (SKIP LOCKED + lease paths are covered by ITs, not benchmarked).
- Webhook dispatch throughput, bulk CSV throughput, and the COD reconciliation query at volume were not benchmarked.
- No PostgreSQL tuning, no separate DB host, no long soak run (each run is 10 s; no GC or bloat behaviour over hours).
- Closed-loop latencies under saturation – not an open-loop SLA measurement.
