# ShipFlow – resume bullets & interview prep

All numbers are from `docs/PERFORMANCE.md` (8 vCPU / 16 GB VM, app and PostgreSQL on the same host, untuned
PostgreSQL, 20-connection pool, medians of 3 × 10 s runs after warm-up). Say "on a single 8-vCPU VM" when quoting them.

## Bullets

- **Built ShipFlow, a carrier-allocation and last-mile delivery engine** (Java 21, Spring Boot 3, PostgreSQL 17):
  idempotent booking, bulk CSV / store adapters, rule-based carrier allocation with stored explanations, a 12-state
  shipment state machine, out-of-order-safe scan ingestion, NDR auto-RTO, COD reconciliation and HMAC-signed outbox
  webhooks with backoff and dead-lettering; 146 unit + integration tests run on real PostgreSQL.
- **Made carrier capacity impossible to oversell under concurrency** with a single conditional UPSERT + CHECK
  constraint, then found the hot capacity row with `pg_stat_activity` and moved the reservation to the end of the
  transaction (optimistic, with retry): booking throughput **519 → 1 006 ops/s (+94 %) and p99 262 → 133 ms at 50
  threads**, with zero overbooking across every benchmark run and a 200-thread concurrency test.
- **Removed lock contention from scan ingestion** by replacing per-scan counter UPSERTs with an append-only outcome
  log folded in batches (exactly-once, SKIP LOCKED): **3 944 → 5 806 scans/s (+47 %) at 50 threads, 3 723 → 6 382
  (+71 %) at 100**, average lock waiters 13 → 0, with SQL invariants proving no duplicate effects.
- **Designed carrier failover that never double-books**: circuit breaker + timeout, carrier calls kept out of DB
  transactions (pool waiters stayed ≤ 1 with a 50 ms carrier call at 50 threads, 805 bookings/s), stable booking
  references, and timeouts reconciled via carrier lookup instead of failing over.
- **Added self-learning carrier scorecards, an explainable 0–100 delivery-risk score and an EDD engine** (cut-offs,
  country weekends/holidays, lane P80 lead times), plus rider routing (sweep clustering + nearest neighbour + 2-opt)
  where 2-opt cut distance **5.8–13.3 % vs nearest neighbour in < 6 ms for 2 000 stops**.

(Do not quote the 82–91 % "vs naive" routing saving without saying the baseline is creation order – it is a strawman.)

## Interview Q&A

**Q: How do you guarantee a carrier's daily capacity is never exceeded with 200 concurrent bookings?**
One statement: `INSERT … ON CONFLICT (carrier, zone, date) DO UPDATE SET reserved = reserved + 1 WHERE reserved <
capacity`. Row-level locking serialises increments of the same row, the `WHERE` makes the increment conditional, and
"0 rows affected" means full. A CHECK `reserved <= capacity` is the safety net. No `SELECT … FOR UPDATE`, no
application lock, works across instances. `ConcurrencyIT` fires 200 bookings at 100 units: exactly 100 ALLOCATED.

**Q: Why did "reserve-last" help so much, and what's the risk?**
A row lock is held until commit. Reserve-first locked the hot row for the whole transaction (inserts, history,
outbox, idempotency update, commit fsync). Reserving last shrinks the hold to roughly the commit. The risk is doing
work for a carrier that just ran out: then the reservation fails, I throw a transient exception, roll back and retry
the whole transaction while still holding the idempotency claim; the final attempt uses reserve-first so it always
terminates. The benchmark never needed a retry, so I can't claim a measured retry cost.

**Q: How is booking idempotent if the client retries while the first request is still running?**
The key is claimed with `INSERT … ON CONFLICT DO NOTHING` in its own committed transaction, so exactly one request
wins immediately. The winner's shipment and the "COMPLETED" marker commit in the same transaction. A concurrent
duplicate sees IN_PROGRESS and waits briefly, then replays or returns 409; a different payload under the same key is
422. Failures delete the claim so the retry can run. Test: 50 threads with one key ⇒ 1 shipment.

**Q: The carrier API timed out. Did it book or not? What do you do?**
Unknown – so I do **not** fail over (that's how duplicate consignments happen). The booking row becomes UNKNOWN; a
reconciler calls the carrier's lookup with the same stable `booking_ref` and settles it. Definitive failures and an
open circuit breaker do fail over to the next-best carrier, after releasing capacity. Carrier calls never run inside a
transaction – the gateway refuses to – so a slow carrier can't exhaust the connection pool.

**Q: Scans arrive duplicated, late and out of order. How do you keep status correct?**
Dedupe on `(carrier, carrier_event_id)` with a unique constraint. A pure `ScanPolicy` compares event time with the
event that produced the current status and the state ranks: older → OUT_OF_ORDER, lower/equal rank → STALE, illegal →
REJECTED, otherwise apply along the shortest legal path and mark skipped states as inferred. Two concurrent scans for
one shipment are serialised by `@Version` optimistic locking with retry.

**Q: Why optimistic locking for scans but an atomic UPSERT for capacity?**
Different contention profiles. One shipment rarely gets concurrent scans, so optimistic is cheap. Capacity is
maximally contended – optimistic version checks would mostly fail – so the database's row lock on a single short
statement is the right tool.

**Q: Your stats are now eventually consistent. Is that OK?**
Scorecards use 30-day windows and Bayesian smoothing; a 1-second lag changes nothing. Correctness is preserved: the
outcome row is written in the same transaction as the transition, and the aggregator deletes and applies a batch in
one transaction, so each outcome is counted exactly once even with multiple aggregators (SKIP LOCKED, sorted keys to
avoid deadlocks). The benchmark checks `statsMismatch = 0`.

**Q: How does the scorecard change allocation?**
Delivered / on-time / first-attempt / RTO counts per carrier-zone-day. Rates are smoothed `(x + prior·k)/(n + k)` so
a carrier with 3 deliveries isn't ranked on noise. They feed the on-time, first-attempt and RTO rules. I found with
a test that min-max cost scoring made the cheapest carrier win regardless of quality, and switched to ratio scoring
(`cheapest/own price`) so quality differences can actually shift volume.

**Q: How do webhooks work if the merchant endpoint is down?**
Transactional outbox: the event row is inserted with the business change, so no event is lost and none is sent for a
rolled-back change. Dispatchers claim due rows with `FOR UPDATE SKIP LOCKED` and a lease, POST outside the transaction
with an HMAC-SHA256 signature over timestamp + body, retry with exponential backoff + jitter, and dead-letter after N
attempts (admin replay). At-least-once; consumers dedupe on the event id. Limitation: no per-shipment ordering.

**Q: Why no Redis / Kafka / PostGIS?**
I measured first. The bottlenecks were a hot row and WAL fsync in PostgreSQL – a cache doesn't fix either. The outbox
gives the same at-least-once delivery without a broker at this scale. Routing is in-memory per hub; haversine with a
bounding-box filter is enough.

**Q: What would you do next for throughput?**
Booking now spends its time in commits (4 per booking) and residual waits on the capacity row. Options: merge the
carrier-booking claim into the create transaction (3 commits), shard capacity into N sub-rows per carrier-day, scale
the pool along with a separate DB host. I'd measure each – I wouldn't turn off `synchronous_commit`.

**Q: What's weak in this project?**
Benchmarks are on one VM with app and DB together and no HTTP layer; routing is compared with a weak baseline;
webhook/carrier secrets are stored in plaintext; failover/reallocation use the simpler reserve-first path; there's no
multi-instance load test.
