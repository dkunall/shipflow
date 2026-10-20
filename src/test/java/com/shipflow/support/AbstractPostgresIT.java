package com.shipflow.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shipflow.carrier.CarrierDirectory;
import com.shipflow.carrier.port.SimulatedCarrierNetwork;
import com.shipflow.carrier.resilience.CircuitBreakerRegistry;
import com.shipflow.network.NetworkDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Integration test base on a REAL PostgreSQL (Testcontainers if Docker, else TEST_DB_URL / local shipflow_test).
 * One shared Spring context; every test starts from truncated tables + a small network fixture:
 * country AE, zone DXB-Z1, hub DXB-HUB, pincodes DXB-001/DXB-002, merchants M1/M2.
 */
@ExtendWith(RequiresPostgres.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class AbstractPostgresIT {
    protected static final String ZONE = "DXB-Z1";
    protected static final String HUB = "DXB-HUB";
    protected static final String PIN = "DXB-001";
    protected static final String PIN2 = "DXB-002";

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper mapper;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected SimulatedCarrierNetwork simulator;
    @Autowired protected CircuitBreakerRegistry breakers;
    @Autowired protected CarrierDirectory carrierDirectory;
    @Autowired protected NetworkDirectory networkDirectory;

    protected UUID m1;
    protected UUID m2;
    protected long hubId;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        TestDatabase.Target t = TestDatabase.get();
        if (t != null) {
            registry.add("spring.datasource.url", t::url);
            registry.add("spring.datasource.username", t::user);
            registry.add("spring.datasource.password", t::password);
        }
    }

    @BeforeEach
    void resetState() {
        jdbc.execute("""
                TRUNCATE route_stop, route_plan, rider, webhook_attempt, webhook_dead_letter, outbox_event,
                         idempotency_record, cod_reconciliation_result, cod_reconciliation_run, cod_remittance_line,
                         cod_remittance, customer_delivery_stats, pincode_delivery_stats, lane_lead_time,
                         carrier_scorecard_daily, delivery_attempt, scan_event, carrier_booking, allocation_decision,
                         shipment_status_history, shipment, carrier_capacity, rate_slab, carrier_zone, carrier,
                         merchant, pincode, hub, zone, holiday
                RESTART IDENTITY CASCADE
                """);
        simulator.reset();
        simulator.setHangDuration(java.time.Duration.ofSeconds(2));
        breakers.resetAll();
        carrierDirectory.invalidate();
        networkDirectory.invalidateCalendars();
        jdbc.update("INSERT INTO zone (code, country_code, name) VALUES (?, 'AE', 'Dubai central')", ZONE);
        hubId = jdbc.queryForObject("""
                INSERT INTO hub (code, name, country_code, lat, lng, cutoff_local_time)
                VALUES (?, 'Dubai Al Quoz hub', 'AE', 25.1500, 55.2300, '23:59') RETURNING id
                """, Long.class, HUB);
        jdbc.update("INSERT INTO pincode (code, zone_code, hub_id, city, lat, lng) VALUES (?, ?, ?, 'Dubai', 25.2048, 55.2708)",
                PIN, ZONE, hubId);
        jdbc.update("INSERT INTO pincode (code, zone_code, hub_id, city, lat, lng) VALUES (?, ?, ?, 'Dubai', 25.0800, 55.1400)",
                PIN2, ZONE, hubId);
        m1 = merchant("Merchant One", null);
        m2 = merchant("Merchant Two", null);
    }

    // ---------- fixtures ----------

    protected UUID merchant(String name, String webhookUrl) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO merchant (id, name, country_code, webhook_url, webhook_secret) VALUES (?, ?, 'AE', ?, ?)",
                id, name, webhookUrl, "whsec_test_" + id);
        return id;
    }

    protected String merchantSecret(UUID id) {
        return "whsec_test_" + id;
    }

    /** Carrier serving ZONE with one 0-5 kg slab at {@code price}, +1.00 per extra 500 g up to 30 kg. */
    protected long carrier(String code, String kind, String integration, String price, int slaDays, int dailyCapacity,
                           boolean cod) {
        long id = jdbc.queryForObject("""
                INSERT INTO carrier (code, name, kind, integration, inbound_webhook_secret) VALUES (?, ?, ?, ?, ?) RETURNING id
                """, Long.class, code, code + " Logistics", kind, integration, "carrier-secret-" + code);
        jdbc.update("""
                INSERT INTO carrier_zone (carrier_id, zone_code, sla_days, cod_supported, max_weight_g, currency,
                                          extra_per_500g, cod_fee_percent, cod_fee_min, default_daily_capacity)
                VALUES (?, ?, ?, ?, 30000, 'AED', 1.00, 1.50, 2.00, ?)
                """, id, ZONE, slaDays, cod, dailyCapacity);
        jdbc.update("INSERT INTO rate_slab (carrier_id, zone_code, up_to_g, price) VALUES (?, ?, 5000, ?)",
                id, ZONE, new BigDecimal(price));
        carrierDirectory.invalidate();
        return id;
    }

    protected long ownFleet(String code, String price, int sla, int cap) {
        return carrier(code, "OWN_FLEET", "OWN_FLEET", price, sla, cap, true);
    }

    protected long thirdParty(String code, String price, int sla, int cap) {
        return carrier(code, "THIRD_PARTY", "SIMULATED_3PL", price, sla, cap, true);
    }

    // ---------- HTTP helpers ----------

    protected static String key() {
        return "key-" + UUID.randomUUID();
    }

    protected String shipmentJson(String orderRef, String paymentMode, String cod) {
        return """
                {"merchantOrderRef":"%s","originHubCode":"%s",
                 "consignee":{"name":"Aisha Khan","phone":"+971501234567","addressLine1":"Villa 12, Street 4B, Al Barsha 2",
                              "landmark":"near Mall of the Emirates","city":"Dubai","pincode":"%s"},
                 "weightGrams":1200,"paymentMode":"%s",%s
                 "declaredValue":{"amount":"150.00","currency":"AED"}}
                """.formatted(orderRef, HUB, PIN, paymentMode,
                cod == null ? "" : "\"codAmount\":{\"amount\":\"" + cod + "\",\"currency\":\"AED\"},");
    }

    protected ResultActions book(UUID merchant, String idemKey, String json) throws Exception {
        return mvc.perform(post("/api/v1/shipments").header("Authorization", TestJwt.merchant(merchant))
                .header("Idempotency-Key", idemKey).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    protected JsonNode bookOk(UUID merchant, String orderRef, String paymentMode, String cod) throws Exception {
        ResultActions r = book(merchant, key(), shipmentJson(orderRef, paymentMode, cod));
        int status = r.andReturn().getResponse().getStatus();
        if (status != 201) {
            throw new AssertionError("booking failed " + status + ": " + r.andReturn().getResponse().getContentAsString());
        }
        return body(r);
    }

    protected ResultActions scan(String auth, String carrierCode, String eventId, String tracking, String code,
                                 Instant at, String ndrReason) throws Exception {
        String json = """
                {"carrierCode":"%s","carrierEventId":"%s","trackingNumber":"%s","eventCode":"%s","occurredAt":"%s",
                 "location":"Dubai"%s}
                """.formatted(carrierCode, eventId, tracking, code, at, ndrReason == null ? "" : ",\"ndrReason\":\"" + ndrReason + "\"");
        return mvc.perform(post("/api/v1/scans").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    protected JsonNode scanOk(String carrierCode, String eventId, String tracking, String code, Instant at,
                              String ndrReason) throws Exception {
        ResultActions r = scan(TestJwt.ops(), carrierCode, eventId, tracking, code, at, ndrReason);
        int status = r.andReturn().getResponse().getStatus();
        if (status != 200) {
            throw new AssertionError("scan failed " + status + ": " + r.andReturn().getResponse().getContentAsString());
        }
        return body(r);
    }

    protected JsonNode getShipment(UUID merchant, String id) throws Exception {
        return body(mvc.perform(get("/api/v1/shipments/{id}", id).header("Authorization", TestJwt.merchant(merchant))));
    }

    protected JsonNode body(ResultActions result) throws Exception {
        String s = result.andReturn().getResponse().getContentAsString();
        return s.isEmpty() ? mapper.nullNode() : mapper.readTree(s);
    }

    protected String statusOf(String shipmentId) {
        return jdbc.queryForObject("SELECT status FROM shipment WHERE id = ?::uuid", String.class, shipmentId);
    }

    protected int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    /** Runs n tasks released simultaneously; returns each result or the thrown exception. */
    protected static <T> List<Object> runConcurrently(int n, IntFunction<Callable<T>> taskFactory) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Callable<T> task = taskFactory.apply(i);
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        return task.call();
                    } catch (Throwable t) {
                        return t;
                    }
                }));
            }
            ready.await(20, TimeUnit.SECONDS);
            go.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> f : futures) {
                results.add(f.get(120, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
