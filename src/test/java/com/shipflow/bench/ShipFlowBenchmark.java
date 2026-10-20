package com.shipflow.bench;

import com.shipflow.ShipFlowApplication;
import com.shipflow.booking.api.ConsigneeDto;
import com.shipflow.booking.api.CreateShipmentRequest;
import com.shipflow.booking.api.MoneyDto;
import com.shipflow.booking.application.ShipmentBookingFacade;
import com.shipflow.carrier.CarrierDirectory;
import com.shipflow.carrier.port.SimulatedCarrierNetwork;
import com.shipflow.carrier.resilience.CircuitBreakerRegistry;
import com.shipflow.network.NetworkDirectory;
import com.shipflow.routing.domain.ClusterThenSequencePlanner;
import com.shipflow.routing.domain.GeoPoint;
import com.shipflow.routing.domain.NaiveBaselinePlanner;
import com.shipflow.routing.domain.NearestNeighborTwoOptSequencer;
import com.shipflow.routing.domain.RiderSlot;
import com.shipflow.routing.domain.RoutingSolution;
import com.shipflow.routing.domain.Stop;
import com.shipflow.routing.domain.SweepClustering;
import com.shipflow.scan.ScanCommand;
import com.shipflow.scan.ScanIngestionService;
import com.shipflow.scan.ScanOutcome;
import com.shipflow.security.Actor;
import com.shipflow.security.Role;
import com.shipflow.shipment.domain.PaymentMode;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Reproducible load benchmark against a REAL local PostgreSQL. Boots the full application context (same beans,
 * transaction boundaries and Hikari pool as production) and drives the application services directly from N
 * platform client threads, so it measures engine + database, not HTTP/JSON/JWT.
 *
 * <p>Scenarios:
 * <ul>
 *   <li>{@code booking}: idempotent create + risk + allocation (atomic capacity reservation) + simulated 3PL booking
 *       (outside the transaction) + confirm; 4 carriers, 40 pincodes; carrier latency 0 ms and 50 ms.</li>
 *   <li>{@code scan}: scan ingestion over pre-created ALLOCATED shipments, each driven through PICKED_UP, IN_TRANSIT,
 *       AT_HUB, OUT_FOR_DELIVERY, DELIVERED (+ ~10% duplicate re-sends), incl. NDR/scorecard/lane listeners.</li>
 *   <li>{@code routing}: in-memory cluster + NN + 2-opt vs the naive baseline on seeded random stops.</li>
 * </ul>
 * Per configuration: fresh data, warm-up (discarded), then R measured runs of D seconds; medians are reported.
 *
 * <p>Run: {@code scripts/run-benchmark.sh} (see docs/PERFORMANCE.md). System properties: bench.pool (20),
 * bench.warmupSec (5), bench.runSec (10), bench.runs (3), bench.threads (50,100,200), bench.scenarios
 * (booking,scan,routing), bench.db.url, bench.db.user; password from env BENCH_DB_PASSWORD (local default
 * "postgres", same as the test default), bench.out (docs/benchmark-results).
 */
public final class ShipFlowBenchmark {
    private static final String DB_URL = System.getProperty("bench.db.url", "jdbc:postgresql://localhost:5432/shipflow_bench");
    private static final String DB_USER = System.getProperty("bench.db.user", "postgres");
    private static final String DB_PASSWORD = Optional.ofNullable(System.getenv("BENCH_DB_PASSWORD")).orElse("postgres");
    private static final int POOL = Integer.getInteger("bench.pool", 20);
    private static final int WARMUP = Integer.getInteger("bench.warmupSec", 5);
    private static final int RUN = Integer.getInteger("bench.runSec", 10);
    private static final int RUNS = Integer.getInteger("bench.runs", 3);
    private static final List<String> SCENARIOS = List.of(System.getProperty("bench.scenarios", "booking,scan,routing").split(","));
    private static final int[] THREADS = Arrays.stream(System.getProperty("bench.threads", "50,100,200").split(","))
            .map(String::trim).mapToInt(Integer::parseInt).toArray();
    private static final String OUT = System.getProperty("bench.out", "docs/benchmark-results");
    private static final int PINCODES = 40;
    private static final boolean RESERVE_LAST = Boolean.parseBoolean(System.getProperty("bench.reserveLast", "true"));

    private ConfigurableApplicationContext ctx;
    private JdbcTemplate jdbc;
    private ShipmentBookingFacade facade;
    private ScanIngestionService scans;
    private com.shipflow.stats.StatsAggregator statsAggregator;
    private SimulatedCarrierNetwork simulator;
    private HikariDataSource pool;
    private UUID merchant;
    private final StringBuilder report = new StringBuilder();
    private final Map<String, String> summary = new LinkedHashMap<>();

    record RunResult(long ok, long errors, double seconds, long[] latNanos, double avgPoolWaiters,
                     double avgLockWaiters, long deadlocks, String firstError, Map<String, Long> extra) {
        double okPerSec() { return ok / seconds; }
        double pct(double p) {
            if (latNanos.length == 0) return Double.NaN;
            int idx = (int) Math.min(latNanos.length - 1, Math.ceil(p / 100.0 * latNanos.length) - 1);
            return latNanos[Math.max(0, idx)] / 1e6;
        }
        double max() { return latNanos.length == 0 ? Double.NaN : latNanos[latNanos.length - 1] / 1e6; }
    }

    @FunctionalInterface
    interface Op {
        void run(int thread) throws Exception;
    }

    public static void main(String[] args) throws Exception {
        new ShipFlowBenchmark().run();
    }

    private void run() throws Exception {
        boot();
        header();
        try {
            for (String s : SCENARIOS) {
                switch (s.trim()) {
                    case "booking" -> bookingScenario();
                    case "scan" -> scanScenario();
                    case "routing" -> routingScenario();
                    default -> throw new IllegalArgumentException("unknown scenario " + s);
                }
            }
            line("## Summary (medians)");
            line("");
            line("| metric | value |");
            line("|---|---|");
            summary.forEach((k, v) -> line("| " + k + " | " + v + " |"));
        } finally {
            Path out = Path.of(OUT, "results-pool" + POOL + "-"
                    + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".md");
            Files.createDirectories(out.getParent());
            Files.writeString(out, report.toString());
            System.out.println("\nResults written to " + out.toAbsolutePath());
            ctx.close();
        }
    }

    // ------------------------------------------------------------------ setup

    private void boot() {
        byte[] secret = new byte[48];
        new SecureRandom().nextBytes(secret); // throwaway per-run HMAC key; no tokens are issued
        Map<String, Object> props = new HashMap<>();
        props.put("spring.datasource.url", DB_URL);
        props.put("spring.datasource.username", DB_USER);
        props.put("spring.datasource.password", DB_PASSWORD);
        props.put("spring.datasource.hikari.maximum-pool-size", POOL);
        props.put("spring.datasource.hikari.minimum-idle", POOL);
        props.put("spring.datasource.hikari.register-mbeans", true);
        props.put("shipflow.jwt.secret", HexFormat.of().formatHex(secret));
        props.put("shipflow.scheduling.enabled", false);
        props.put("shipflow.allocation.reserve-last", RESERVE_LAST);
        props.put("server.port", 0);
        props.put("logging.level.root", "WARN");
        props.put("logging.level.com.shipflow", "ERROR");
        props.forEach((k, v) -> System.setProperty(k, String.valueOf(v)));
        SpringApplication app = new SpringApplication(ShipFlowApplication.class);
        app.setLogStartupInfo(false);
        ctx = app.run();
        jdbc = ctx.getBean(JdbcTemplate.class);
        facade = ctx.getBean(ShipmentBookingFacade.class);
        scans = ctx.getBean(ScanIngestionService.class);
        statsAggregator = ctx.getBean(com.shipflow.stats.StatsAggregator.class);
        simulator = ctx.getBean(SimulatedCarrierNetwork.class);
        pool = ctx.getBean(HikariDataSource.class);
    }

    /** Fresh network: 1 hub, 1 zone, 40 pincodes, 4 carriers (different price/SLA), 1 merchant with a webhook URL. */
    private void reset(int capacityPerCarrier) {
        jdbc.execute("""
                TRUNCATE delivery_outcome, route_stop, route_plan, rider, webhook_attempt, webhook_dead_letter, outbox_event,
                         idempotency_record, cod_reconciliation_result, cod_reconciliation_run, cod_remittance_line,
                         cod_remittance, customer_delivery_stats, pincode_delivery_stats, lane_lead_time,
                         carrier_scorecard_daily, delivery_attempt, scan_event, carrier_booking, allocation_decision,
                         shipment_status_history, shipment, carrier_capacity, rate_slab, carrier_zone, carrier,
                         merchant, pincode, hub, zone, holiday
                RESTART IDENTITY CASCADE
                """);
        jdbc.update("INSERT INTO zone (code, country_code, name) VALUES ('DXB-Z1', 'AE', 'Dubai')");
        long hub = jdbc.queryForObject("""
                INSERT INTO hub (code, name, country_code, lat, lng, cutoff_local_time)
                VALUES ('DXB-HUB', 'Dubai hub', 'AE', 25.15, 55.23, '23:59') RETURNING id""", Long.class);
        Random r = new Random(1);
        for (int i = 0; i < PINCODES; i++) {
            jdbc.update("INSERT INTO pincode (code, zone_code, hub_id, city, lat, lng) VALUES (?, 'DXB-Z1', ?, 'Dubai', ?, ?)",
                    "P" + i, hub, 25.0 + r.nextDouble() * 0.3, 55.05 + r.nextDouble() * 0.4);
        }
        String[][] carriers = {{"OWNF", "OWN_FLEET", "OWN_FLEET", "9.00", "1"}, {"ARAMEXSIM", "THIRD_PARTY", "SIMULATED_3PL", "11.00", "2"},
                {"SMSASIM", "THIRD_PARTY", "SIMULATED_3PL", "10.00", "3"}, {"QUIQSIM", "THIRD_PARTY", "SIMULATED_3PL", "12.50", "1"}};
        for (String[] c : carriers) {
            long id = jdbc.queryForObject("""
                    INSERT INTO carrier (code, name, kind, integration, inbound_webhook_secret) VALUES (?, ?, ?, ?, ?) RETURNING id
                    """, Long.class, c[0], c[0], c[1], c[2], "bench-inbound-" + c[0]);
            jdbc.update("""
                    INSERT INTO carrier_zone (carrier_id, zone_code, sla_days, cod_supported, max_weight_g, currency,
                                              extra_per_500g, cod_fee_percent, cod_fee_min, default_daily_capacity)
                    VALUES (?, 'DXB-Z1', ?, true, 30000, 'AED', 1.00, 1.50, 2.00, ?)""", id, Integer.parseInt(c[4]), capacityPerCarrier);
            jdbc.update("INSERT INTO rate_slab (carrier_id, zone_code, up_to_g, price) VALUES (?, 'DXB-Z1', 2000, ?), (?, 'DXB-Z1', 5000, ?)",
                    id, new BigDecimal(c[3]), id, new BigDecimal(c[3]).add(new BigDecimal("4.00")));
        }
        merchant = UUID.randomUUID();
        jdbc.update("INSERT INTO merchant (id, name, country_code, webhook_url, webhook_secret) VALUES (?, 'Bench', 'AE', 'http://127.0.0.1:9/hook', 'whsec_bench')",
                merchant);
        ctx.getBean(CarrierDirectory.class).invalidate();
        ctx.getBean(NetworkDirectory.class).invalidateCalendars();
        ctx.getBean(CircuitBreakerRegistry.class).resetAll();
        simulator.reset();
        jdbc.execute("VACUUM ANALYZE");
    }

    private static final AtomicLong SEQ = new AtomicLong();

    private CreateShipmentRequest request(Random r) {
        long n = SEQ.incrementAndGet();
        boolean cod = r.nextInt(100) < 60; // GCC e-commerce is COD-heavy
        BigDecimal value = BigDecimal.valueOf(50 + r.nextInt(900)).setScale(2);
        return new CreateShipmentRequest("ORD-" + n, "DXB-HUB",
                new ConsigneeDto("Customer " + n, "+9715" + (10_000_000 + r.nextInt(89_999_999)),
                        "Flat " + r.nextInt(2000) + ", Tower " + r.nextInt(50) + ", Street " + r.nextInt(80),
                        null, r.nextBoolean() ? "near metro" : null, "Dubai", "P" + r.nextInt(PINCODES), null, null),
                200 + r.nextInt(6000), cod ? PaymentMode.COD : PaymentMode.PREPAID,
                cod ? new MoneyDto(value, "AED") : null, new MoneyDto(value, "AED"));
    }

    private Actor actor() {
        return new Actor("bench-merchant", Set.of(Role.MERCHANT), Optional.of(merchant));
    }

    private void header() throws Exception {
        String cpu = Files.readAllLines(Path.of("/proc/cpuinfo")).stream().filter(l -> l.startsWith("model name"))
                .findFirst().map(l -> l.substring(l.indexOf(':') + 1).trim()).orElse("?");
        String mem = Files.readAllLines(Path.of("/proc/meminfo")).get(0).replaceAll("\\s+", " ");
        String pg = jdbc.queryForObject("SELECT version()", String.class);
        Map<String, String> settings = new LinkedHashMap<>();
        for (String s : List.of("max_connections", "shared_buffers", "synchronous_commit", "fsync", "wal_level")) {
            settings.put(s, jdbc.queryForObject("SHOW " + s, String.class));
        }
        line("# ShipFlow benchmark run " + Instant.now().truncatedTo(ChronoUnit.SECONDS));
        line("");
        line("- CPU: " + Runtime.getRuntime().availableProcessors() + " vCPU, " + cpu);
        line("- " + mem);
        line("- JVM: " + System.getProperty("java.vm.name") + " " + System.getProperty("java.version")
                + ", max heap " + Runtime.getRuntime().maxMemory() / (1024 * 1024) + " MB");
        line("- PostgreSQL: " + pg + " (same host as the JVM)");
        line("- PG settings: " + settings);
        line("- Hikari maximum-pool-size: " + POOL + "; allocation reserve-last: " + RESERVE_LAST);
        line("- Warm-up " + WARMUP + " s, then " + RUNS + " measured runs x " + RUN + " s per configuration; fresh data per run");
        line("");
    }

    // ------------------------------------------------------------------ scenarios

    private void bookingScenario() throws Exception {
        line("## (1) Booking + allocation (idempotent create, risk, allocation with atomic capacity reserve, carrier booking, confirm)");
        line("");
        line("Each op = `ShipmentBookingFacade.create` with a fresh Idempotency-Key: idempotency claim tx, create+allocate tx "
                + "(shipment, history, outbox, capacity upsert, decision), simulated 3PL call outside any tx, confirm tx. "
                + "4 carriers x 1 zone => every booking contends on the same few `carrier_capacity` rows. 60% COD.");
        line("");
        tableHeader();
        for (int carrierMs : new int[]{0, 50}) {
            for (int threads : THREADS) {
                String label = "booking (3PL latency " + carrierMs + " ms)";
                List<RunResult> runs = measure(() -> {
                    reset(10_000_000);
                    for (String c : List.of("ARAMEXSIM", "SMSASIM", "QUIQSIM")) {
                        simulator.configure(c, SimulatedCarrierNetwork.Mode.OK, Duration.ofMillis(carrierMs));
                    }
                    jdbc.update("UPDATE carrier SET active = false WHERE code = 'OWNF'"); // force the 3PL path
                    ctx.getBean(CarrierDirectory.class).invalidate();
                    return t -> {
                        Random r = RANDOMS.get();
                        var res = facade.create(actor(), merchant, "bench-" + UUID.randomUUID(), request(r), "API", null);
                        if (res.replayed()) {
                            throw new IllegalStateException("unexpected replay");
                        }
                    };
                }, threads, this::bookingInvariants);
                tableRows(label, threads, runs);
                summary.put(label + ", " + threads + " threads: ops/s | p50 | p95 | p99 ms", medians(runs));
            }
        }
        line("");
        line("Invariant columns: `overbooked` = capacity rows with reserved > capacity; `alloc!=reserved` = |sum(reserved) - "
                + "RESERVED decisions|; `unconfirmed` = ALLOCATED shipments without AWB at end of run (in-flight at stop).");
        line("");
    }

    private long racesSeen;

    private Map<String, Long> bookingInvariants() {
        Map<String, Long> m = new LinkedHashMap<>();
        m.put("overbooked", jdbc.queryForObject("SELECT count(*) FROM carrier_capacity WHERE reserved > capacity", Long.class));
        long reserved = jdbc.queryForObject("SELECT coalesce(sum(reserved),0) FROM carrier_capacity", Long.class);
        long decisions = jdbc.queryForObject("SELECT count(*) FROM allocation_decision WHERE outcome = 'RESERVED'", Long.class);
        m.put("alloc!=reserved", Math.abs(reserved - decisions));
        m.put("unconfirmed", jdbc.queryForObject("SELECT count(*) FROM shipment WHERE status='ALLOCATED' AND carrier_awb IS NULL", Long.class));
        long races = (long) ctx.getBean(io.micrometer.core.instrument.MeterRegistry.class)
                .find("shipflow.allocations").tag("result", "race_lost").counters().stream()
                .mapToDouble(io.micrometer.core.instrument.Counter::count).sum();
        m.put("raceRetries", races - racesSeen);
        racesSeen = races;
        m.put("dupConsignments", jdbc.queryForObject("""
                SELECT count(*) FROM (SELECT shipment_id FROM carrier_booking WHERE status='CONFIRMED'
                 GROUP BY shipment_id HAVING count(*) > 1) x""", Long.class));
        return m;
    }

    private static final ThreadLocal<Random> RANDOMS = ThreadLocal.withInitial(Random::new);
    private static final String[] FLOW = {"PICKED_UP", "IN_TRANSIT", "AT_HUB", "OUT_FOR_DELIVERY", "DELIVERED"};

    private void scanScenario() throws Exception {
        line("## (2) Scan ingestion (idempotent by carrier event id, out-of-order safe, optimistic locking)");
        line("");
        line("Pre-created ALLOCATED shipments (one template booked through the real path, cloned with SQL for speed). "
                + "Each thread walks its own shipments through 5 scans; ~10% of scans are re-sent duplicates. "
                + "Each op = one `ScanIngestionService.ingest`. DELIVERED triggers NDR/scorecard/lane-lead-time listeners and an outbox row.");
        line("");
        line("Statistics are write-behind: like the production scheduler, a background thread folds `delivery_outcome` "
                + "into the scorecard/lane/risk counters every 1 s during the run (its DB work competes with the scans).");
        line("");
        tableHeader();
        ScheduledExecutorService flusher = Executors.newSingleThreadScheduledExecutor();
        flusher.scheduleWithFixedDelay(this::flushStats, 1, 1, TimeUnit.SECONDS);
        try {
        for (int threads : THREADS) {
            List<RunResult> runs = measure(() -> {
                reset(10_000_000);
                int perRun = 120_000;
                List<String> tracking = cloneAllocatedShipments(perRun);
                ConcurrentLinkedQueue<String> queue = new ConcurrentLinkedQueue<>(tracking);
                String carrier = jdbc.queryForObject("SELECT c.code FROM shipment s JOIN carrier c ON c.id = s.carrier_id LIMIT 1", String.class);
                ThreadLocal<int[]> step = ThreadLocal.withInitial(() -> new int[]{FLOW.length});
                ThreadLocal<String[]> current = new ThreadLocal<>();
                Instant base = Instant.now().minus(6, ChronoUnit.HOURS);
                return t -> {
                    int[] st = step.get();
                    Random r = RANDOMS.get();
                    if (st[0] >= FLOW.length) {
                        String next = queue.poll();
                        if (next == null) {
                            throw new IllegalStateException("benchmark ran out of shipments");
                        }
                        current.set(new String[]{next});
                        st[0] = 0;
                    }
                    String trk = current.get()[0];
                    boolean duplicate = st[0] > 0 && r.nextInt(10) == 0;
                    int idx = duplicate ? st[0] - 1 : st[0];
                    var res = scans.ingest(new ScanCommand(carrier, trk + "-" + idx, trk, null, FLOW[idx],
                            base.plusSeconds(idx * 60L), "Dubai", null), "CARRIER_WEBHOOK", "bench");
                    if (duplicate) {
                        if (res.outcome() != ScanOutcome.DUPLICATE) throw new IllegalStateException("expected DUPLICATE");
                    } else {
                        if (res.outcome() != ScanOutcome.APPLIED) throw new IllegalStateException("expected APPLIED got " + res.outcome());
                        st[0]++;
                    }
                };
            }, threads, this::scanInvariants);
            tableRows("scan ingest", threads, runs);
            summary.put("scan ingest, " + threads + " threads: ops/s | p50 | p95 | p99 ms", medians(runs));
        }
        } finally {
            flusher.shutdownNow();
            flusher.awaitTermination(30, TimeUnit.SECONDS);
        }
        line("");
        line("Invariant columns: `dupEffects` = scan events stored twice for one (carrier, event id) (must be 0); "
                + "`historyMismatch` = applied non-duplicate scans without exactly one history row (must be 0); "
                + "`statsMismatch` = |scorecard delivered + pending outcomes - DELIVERED shipments| after a final flush (must be 0).");
        line("");
    }

    /** Serialised so the invariant check never races a scheduled flush that has claimed rows but not yet committed. */
    private synchronized void flushStats() {
        try {
            statsAggregator.drainAll();
        } catch (RuntimeException e) {
            System.err.println("stats flush failed: " + e);
        }
    }

    private Map<String, Long> scanInvariants() {
        Map<String, Long> m = new LinkedHashMap<>();
        m.put("dupEffects", jdbc.queryForObject("""
                SELECT count(*) FROM (SELECT carrier_id, carrier_event_id FROM scan_event GROUP BY 1, 2 HAVING count(*) > 1) x""", Long.class));
        long applied = jdbc.queryForObject("SELECT count(*) FROM scan_event WHERE outcome = 'APPLIED'", Long.class);
        long history = jdbc.queryForObject("SELECT count(*) FROM shipment_status_history WHERE actor = 'bench'", Long.class);
        m.put("historyMismatch", Math.abs(applied - history));
        long delivered = jdbc.queryForObject("SELECT count(*) FROM shipment WHERE status = 'DELIVERED'", Long.class);
        m.put("delivered", delivered);
        flushStats();
        long folded = jdbc.queryForObject("SELECT coalesce(sum(delivered),0) FROM carrier_scorecard_daily", Long.class);
        m.put("statsMismatch", Math.abs(folded - delivered));
        return m;
    }

    /** Books one shipment through the real path, then clones it n times (ALLOCATED, own tracking numbers). */
    private List<String> cloneAllocatedShipments(int n) {
        UUID template = facade.create(actor(), merchant, "bench-template", request(new Random(3)), "API", null).shipmentId();
        List<String> cols = jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns WHERE table_name = 'shipment' AND table_schema = current_schema()
                 ORDER BY ordinal_position""", String.class);
        StringBuilder select = new StringBuilder();
        for (String c : cols) {
            if (!select.isEmpty()) select.append(", ");
            switch (c) {
                case "id" -> select.append("gen_random_uuid()");
                case "merchant_order_ref" -> select.append("'CLONE-' || g");
                case "tracking_number" -> select.append("'SFB' || lpad(g::text, 11, '0')");
                case "carrier_awb" -> select.append("'AWB-' || g");
                default -> select.append("t.").append(c);
            }
        }
        jdbc.update("INSERT INTO shipment (" + String.join(", ", cols) + ") SELECT " + select
                + " FROM shipment t, generate_series(1, ?) g WHERE t.id = ?", n, template);
        jdbc.execute("VACUUM ANALYZE shipment");
        return jdbc.queryForList("SELECT tracking_number FROM shipment WHERE merchant_order_ref LIKE 'CLONE-%' ORDER BY random()", String.class);
    }

    private void routingScenario() {
        line("## (3) Rider batching + routing vs naive baseline (pure, in-memory)");
        line("");
        line("Seeded random stops in a ~35 x 40 km box around the hub, riders with capacity 25. Naive = creation order, "
                + "fill rider by rider, visit in that order. Optimised = capacity-balanced sweep clustering + nearest neighbour + 2-opt. "
                + "Distances are closed tours (hub -> stops -> hub), haversine km. Runtime = median of " + RUNS
                + " timed repetitions after 3 warm-up repetitions; km are deterministic per seed (median over 5 seeds shown).");
        line("");
        line("| stops | riders | naive km | NN only km | NN + 2-opt km | saving vs naive | optimised runtime ms (median) | naive runtime ms |");
        line("|---|---|---|---|---|---|---|---|");
        GeoPoint depot = new GeoPoint(25.15, 55.23);
        for (int n : new int[]{100, 500, 1000, 2000}) {
            int ridersN = (n + 24) / 25;
            List<RiderSlot> riders = new ArrayList<>();
            for (int i = 0; i < ridersN; i++) riders.add(new RiderSlot("r" + i, 25));
            var opt = new ClusterThenSequencePlanner(new SweepClustering(), new NearestNeighborTwoOptSequencer());
            var nn = new ClusterThenSequencePlanner(new SweepClustering(), new NearestNeighborTwoOptSequencer(0));
            var naive = new NaiveBaselinePlanner();
            double[] naiveKm = new double[5];
            double[] nnKm = new double[5];
            double[] optKm = new double[5];
            for (int seed = 0; seed < 5; seed++) {
                List<Stop> stops = stops(n, seed);
                naiveKm[seed] = naive.solve(depot, stops, riders).totalKm();
                nnKm[seed] = nn.solve(depot, stops, riders).totalKm();
                optKm[seed] = opt.solve(depot, stops, riders).totalKm();
            }
            List<Stop> s0 = stops(n, 0);
            double optMs = timeMs(() -> opt.solve(depot, s0, riders));
            double naiveMs = timeMs(() -> naive.solve(depot, s0, riders));
            double saving = 100.0 * (median(naiveKm) - median(optKm)) / median(naiveKm);
            line(String.format("| %d | %d | %.1f | %.1f | %.1f | %.1f %% | %.2f | %.3f |", n, ridersN, median(naiveKm),
                    median(nnKm), median(optKm), saving, optMs, naiveMs));
            summary.put("routing " + n + " stops: saving vs naive | runtime ms", String.format("%.1f %% | %.2f", saving, optMs));
        }
        line("");
    }

    private double timeMs(Supplier<RoutingSolution> s) {
        for (int i = 0; i < 3; i++) s.get();
        double[] t = new double[Math.max(RUNS, 3)];
        for (int i = 0; i < t.length; i++) {
            long t0 = System.nanoTime();
            s.get();
            t[i] = (System.nanoTime() - t0) / 1e6;
        }
        return median(t);
    }

    private static List<Stop> stops(int n, long seed) {
        Random r = new Random(seed);
        List<Stop> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new Stop("s" + i, new GeoPoint(25.0 + r.nextDouble() * 0.32, 55.03 + r.nextDouble() * 0.40)));
        }
        return out;
    }

    // ------------------------------------------------------------------ measurement core

    private List<RunResult> measure(Supplier<Op> setup, int threads, Supplier<Map<String, Long>> invariants) throws Exception {
        List<RunResult> results = new ArrayList<>();
        for (int run = 0; run <= RUNS; run++) {
            Op op = setup.get();
            RunResult r = drive(op, threads, run == 0 ? WARMUP : RUN, invariants);
            if (run > 0) {
                results.add(r);
            }
        }
        return results;
    }

    private RunResult drive(Op op, int threads, int seconds, Supplier<Map<String, Long>> invariants) throws Exception {
        AtomicBoolean stop = new AtomicBoolean();
        AtomicBoolean measuring = new AtomicBoolean();
        long[][] lat = new long[threads][];
        long[] ok = new long[threads];
        long[] errors = new long[threads];
        String[] firstError = new String[1];
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> ts = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int id = t;
            Thread th = new Thread(() -> {
                LongList l = new LongList();
                try {
                    start.await();
                } catch (InterruptedException e) {
                    return;
                }
                while (!stop.get()) {
                    long t0 = System.nanoTime();
                    boolean success = true;
                    try {
                        op.run(id);
                    } catch (Exception e) {
                        success = false;
                        synchronized (firstError) {
                            if (firstError[0] == null) firstError[0] = e.getClass().getSimpleName() + ": " + e.getMessage();
                        }
                    }
                    long dt = System.nanoTime() - t0;
                    if (!measuring.get()) continue;
                    if (success) {
                        ok[id]++;
                        l.add(dt);
                    } else {
                        errors[id]++;
                    }
                }
                lat[id] = l.toArray();
            });
            th.start();
            ts.add(th);
        }
        long dl0 = deadlocks();
        start.countDown();
        measuring.set(true);
        long t0 = System.nanoTime();
        long samples = 0;
        long poolSum = 0;
        long lockSum = 0;
        try (Connection c = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD); Statement st = c.createStatement()) {
            long end = t0 + seconds * 1_000_000_000L;
            while (System.nanoTime() < end) {
                ResultSet rs = st.executeQuery("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock'");
                rs.next();
                lockSum += rs.getInt(1);
                poolSum += pool.getHikariPoolMXBean().getThreadsAwaitingConnection();
                samples++;
                Thread.sleep(100);
            }
        }
        measuring.set(false);
        double secs = (System.nanoTime() - t0) / 1e9;
        stop.set(true);
        for (Thread th : ts) th.join();
        return new RunResult(Arrays.stream(ok).sum(), Arrays.stream(errors).sum(), secs, merge(lat),
                samples == 0 ? 0 : (double) poolSum / samples, samples == 0 ? 0 : (double) lockSum / samples,
                deadlocks() - dl0, firstError[0], invariants.get());
    }

    private long deadlocks() {
        Long n = jdbc.queryForObject("SELECT deadlocks FROM pg_stat_database WHERE datname = current_database()", Long.class);
        return n == null ? 0 : n;
    }

    // ------------------------------------------------------------------ output

    private void tableHeader() {
        line("| scenario | threads | run | ok ops/s | p50 ms | p95 ms | p99 ms | max ms | avg pool waiters | avg lock waiters | deadlocks | errors | invariants |");
        line("|---|---|---|---|---|---|---|---|---|---|---|---|---|");
    }

    private void tableRows(String label, int threads, List<RunResult> runs) {
        int i = 1;
        for (RunResult r : runs) {
            line(String.format("| %s | %d | %d | %.0f | %.2f | %.2f | %.2f | %.1f | %.1f | %.1f | %d | %d | %s |",
                    label, threads, i++, r.okPerSec(), r.pct(50), r.pct(95), r.pct(99), r.max(), r.avgPoolWaiters(),
                    r.avgLockWaiters(), r.deadlocks(), r.errors(), r.extra()));
            if (r.firstError() != null) {
                line("<!-- first error: " + r.firstError().replace("--", "-") + " -->");
            }
        }
        line(String.format("| **%s median** | %d | - | **%s** | | | | | | | | | |", label, threads, medians(runs)));
    }

    private static String medians(List<RunResult> runs) {
        return String.format("%.0f | %.2f | %.2f | %.2f", median(runs.stream().mapToDouble(RunResult::okPerSec).toArray()),
                median(runs.stream().mapToDouble(r -> r.pct(50)).toArray()),
                median(runs.stream().mapToDouble(r -> r.pct(95)).toArray()),
                median(runs.stream().mapToDouble(r -> r.pct(99)).toArray()));
    }

    private static double median(double[] v) {
        double[] s = v.clone();
        Arrays.sort(s);
        return s.length % 2 == 1 ? s[s.length / 2] : (s[s.length / 2 - 1] + s[s.length / 2]) / 2;
    }

    private void line(String s) {
        System.out.println(s);
        report.append(s).append('\n');
    }

    private static long[] merge(long[][] parts) {
        int n = 0;
        for (long[] p : parts) n += p == null ? 0 : p.length;
        long[] all = new long[n];
        int o = 0;
        for (long[] p : parts) {
            if (p == null) continue;
            System.arraycopy(p, 0, all, o, p.length);
            o += p.length;
        }
        Arrays.sort(all);
        return all;
    }

    static final class LongList {
        private long[] a = new long[1 << 14];
        private int n;

        void add(long v) {
            if (n == a.length) a = Arrays.copyOf(a, n * 2);
            a[n++] = v;
        }

        long[] toArray() {
            return Arrays.copyOf(a, n);
        }
    }
}
