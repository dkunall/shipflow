# Performance

Every number below was measured on Oct 29, 2026 on one shared dev box. The raw output is in
[benchmark-results/](benchmark-results/) and [demo-output.txt](demo-output.txt). Read these as single-machine dev
numbers, not as a capacity claim.

## Setup

| | |
|---|---|
| box | 8 vCPU Xeon VM, 16 GB RAM, **shared**: other projects' containers were running, and the load average was around 20 during the runs |
| layout | client, JVM and PostgreSQL all on the same box |
| JVM | OpenJDK 21.0.13, 1 GB heap for the benchmark, 512 MB for the app container |
| PostgreSQL | 17.11 with untuned defaults (`shared_buffers=128MB`, `synchronous_commit=on`, `fsync=on`) |
| pool | HikariCP, 20 connections |

## 1. HTTP end to end (`scripts/loadtest.py`, run by `scripts/demo.sh`)

The full stack runs in Docker Compose. The load test sends 400 PREPAID bookings from 16 concurrent clients, each
with its own Idempotency-Key and JWT. This covers HTTP, JSON, auth, idempotency, allocation, the simulated carrier
call and confirmation.

| run | req/s | p50 ms | p95 ms | p99 ms | max ms | non-201 |
|---|---|---|---|---|---|---|
| 1 | 198.4 | 68.3 | 151.2 | 203.7 | 235.0 | 0 |
| 2 | 159.2 | 85.8 | 214.4 | 271.9 | 305.2 | 0 |
| 3 | 112.9 | 124.9 | 255.7 | 302.0 | 407.7 | 0 |
| 4 | 137.3 | 114.2 | 177.8 | 216.9 | 245.5 | 0 |

The spread between runs comes from the shared box, not from the code. A cold first run, right after the JVM
started, measured 86 req/s.

## 2. In-process benchmark (`scripts/run-benchmark.sh`)

`ShipFlowBenchmark` boots the whole Spring context against the `shipflow_bench` database and calls the services
directly, so HTTP is not included. It's a closed loop: N threads, 5 s of warm-up, then one 10 s run per row. Each
run ends with SQL invariant checks, and all of them were 0 on every row:
- overbooked capacity
- duplicate consignments
- unconfirmed bookings
- double-applied scans
- history mismatches
- stats mismatches

The workload is deliberately hostile. Four carriers share one zone, so every booking fights over the same few
`carrier_capacity` rows. All scans land on one lane.

The command used:

```bash
BENCH_DB_PASSWORD=postgres scripts/run-benchmark.sh -Dbench.heap=1g -Dbench.runs=1 -Dbench.threads=50,100
```

| workload | threads | ops/s | p50 ms | p95 ms | p99 ms |
|---|---|---|---|---|---|
| booking, 3PL 0 ms | 50 | 311 | 133 | 340 | 644 |
| booking, 3PL 0 ms | 100 | 432 | 203 | 484 | 649 |
| booking, 3PL 50 ms | 50 | 385 | 119 | 209 | 255 |
| booking, 3PL 50 ms | 100 | 361 | 250 | 487 | 645 |
| scan ingest (about 10% duplicates) | 50 | 1499 | 18 | 100 | 161 |
| scan ingest (about 10% duplicates) | 100 | 1887 | 17 | 188 | 297 |

At these thread counts the 20-connection pool is the limit: 30 to 80 threads were waiting for a connection on
average.

### Reserve-last vs reserve-first (ADR-003)

This compares the same code on the same box, a few minutes apart, with `-Dbench.reserveLast=false`.

| 3PL latency | threads | reserve-first ops/s | reserve-last ops/s | reserve-first p99 | reserve-last p99 | avg lock waiters (first → last) |
|---|---|---|---|---|---|---|
| 0 ms | 50 | 253 | 311 | 568 | 644 | 15.1 → 7.6 |
| 0 ms | 100 | 283 | 432 | 899 | 649 | 15.9 → 7.3 |
| 50 ms | 50 | 215 | 385 | 612 | 255 | 15.6 → 6.6 |
| 50 ms | 100 | 183 | 361 | 1246 | 645 | 14.5 → 6.6 |

Reserving capacity as the last statement of the transaction roughly halves the number of backends waiting on the
capacity row lock. It also gives 23–97% more bookings per second. Each row is a single run, so treat the exact
percentages loosely.

## 3. Routing (pure, in-memory)

The stops are seeded at random in a box of about 35 × 40 km around the hub, and each rider has capacity 25.

| stops | riders | naive km | nearest neighbour km | NN + 2-opt km | runtime ms |
|---|---|---|---|---|---|
| 100 | 4 | 2093.8 | 431.6 | 374.8 | 0.61 |
| 500 | 20 | 10140.3 | 1225.6 | 1062.6 | 1.19 |
| 1000 | 40 | 20391.6 | 2060.1 | 1881.7 | 3.38 |
| 2000 | 80 | 40363.4 | 3762.0 | 3544.6 | 5.81 |

The 82–91% saving against "naive" is against a strawman: naive visits stops in creation order. The fair number is
what 2-opt adds on top of nearest neighbour, which is 6–13%. I haven't compared against an optimal solver.

## Not measured

- Multiple app instances
- A dedicated database host
- Postgres tuning
- Soak or long-running tests
- Anything over a real network
