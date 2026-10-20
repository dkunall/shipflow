package com.shipflow.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.shipflow.security.HmacSigner;
import com.shipflow.support.AbstractPostgresIT;
import com.shipflow.support.TestJwt;
import com.shipflow.webhook.WebhookDispatcher;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real HTTP delivery to a local merchant endpoint (JDK HttpServer) through the real HttpClient transport. */
class WebhookDeliveryIT extends AbstractPostgresIT {
    @Autowired WebhookDispatcher dispatcher;

    private HttpServer server;
    private final ConcurrentLinkedQueue<Received> received = new ConcurrentLinkedQueue<>();
    private final AtomicInteger failNext = new AtomicInteger();
    private UUID hooked;

    record Received(String eventId, String signature, String type, String attempt, String body) {
    }

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(16));
        server.createContext("/hook", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            int code;
            if (failNext.get() > 0 && failNext.getAndDecrement() > 0) {
                code = 500;
            } else {
                received.add(new Received(ex.getRequestHeaders().getFirst("X-ShipFlow-Event-Id"),
                        ex.getRequestHeaders().getFirst("X-ShipFlow-Signature"),
                        ex.getRequestHeaders().getFirst("X-ShipFlow-Event-Type"),
                        ex.getRequestHeaders().getFirst("X-ShipFlow-Attempt"), body));
                code = 204;
            }
            ex.sendResponseHeaders(code, -1);
            ex.close();
        });
        server.start();
        hooked = merchant("Hooked", "http://127.0.0.1:" + server.getAddress().getPort() + "/hook");
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void drain() {
        while (dispatcher.dispatchOnce() > 0) {
            // loop until nothing is due
        }
    }

    @Test
    void lifecycleEventsAreDeliveredSignedWithStableEventIds() throws Exception {
        thirdParty("FAST", "10.00", 2, 10);
        bookOk(hooked, "W-1", "PREPAID", null);
        bookOk(m1, "NO-HOOK", "PREPAID", null); // merchant without endpoint => no outbox rows
        assertThat(count("SELECT count(*) FROM outbox_event WHERE merchant_id <> ?", hooked)).isZero();
        int events = count("SELECT count(*) FROM outbox_event");
        assertThat(events).isGreaterThanOrEqualTo(3);
        drain();
        assertThat(received).hasSize(events);
        assertThat(received.stream().map(Received::type).collect(Collectors.toSet()))
                .contains("shipment.created", "shipment.status_changed", "shipment.carrier_booked");
        for (Received r : received) {
            assertThat(HmacSigner.verify(merchantSecret(hooked), r.signature(), r.body(), Instant.now().getEpochSecond(), 300))
                    .as("signature of %s", r.eventId()).isTrue();
            JsonNode payload = mapper.readTree(r.body());
            assertThat(payload.get("id").asText()).isEqualTo(r.eventId());
            assertThat(r.attempt()).isEqualTo("1");
        }
        assertThat(count("SELECT count(*) FROM outbox_event WHERE status = 'DELIVERED'")).isEqualTo(events);
        drain();
        assertThat(received).hasSize(events); // delivered events are never re-sent
    }

    @Test
    void failuresBackOffThenDeadLetterAndCanBeReplayed() throws Exception {
        UUID eventId = insertEvents(1).get(0);
        failNext.set(100);
        dispatcher.dispatchOnce();
        Map<String, Object> row = jdbc.queryForMap("SELECT status, attempts, next_attempt_at, last_error FROM outbox_event");
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("attempts")).isEqualTo(1);
        assertThat(((java.sql.Timestamp) row.get("next_attempt_at")).toInstant()).isAfter(Instant.now());
        assertThat(dispatcher.dispatchOnce()).isZero(); // not due yet
        for (int i = 2; i <= 8; i++) {
            jdbc.update("UPDATE outbox_event SET next_attempt_at = now() - interval '1 second'");
            dispatcher.dispatchOnce();
        }
        assertThat(jdbc.queryForObject("SELECT status FROM outbox_event", String.class)).isEqualTo("DEAD");
        assertThat(count("SELECT count(*) FROM webhook_dead_letter")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM webhook_attempt WHERE event_id = ?", eventId)).isEqualTo(8);
        assertThat(received).isEmpty();

        failNext.set(0);
        mvc.perform(post("/api/v1/admin/webhooks/dead-letters/{id}/replay", eventId).header("Authorization", TestJwt.admin()))
                .andExpect(status().is2xxSuccessful());
        drain();
        assertThat(received).hasSize(1);
        assertThat(received.peek().eventId()).isEqualTo(eventId.toString());
        assertThat(jdbc.queryForObject("SELECT status FROM outbox_event", String.class)).isEqualTo("DELIVERED");
    }

    @Test
    void concurrentDispatchersDeliverEachEventExactlyOnce() throws Exception {
        List<UUID> ids = insertEvents(400);
        runConcurrently(8, i -> () -> {
            drain();
            return null;
        });
        Map<String, Long> perEvent = received.stream()
                .collect(Collectors.groupingBy(Received::eventId, ConcurrentHashMap::new, Collectors.counting()));
        assertThat(perEvent).hasSize(ids.size());
        assertThat(perEvent.values()).allMatch(n -> n == 1L);
        assertThat(count("SELECT count(*) FROM outbox_event WHERE status = 'DELIVERED'")).isEqualTo(400);
    }

    private List<UUID> insertEvents(int n) {
        return jdbc.queryForList("""
                INSERT INTO outbox_event (id, merchant_id, aggregate_id, event_type, payload, status, attempts,
                                          next_attempt_at, created_at)
                SELECT x.id, ?, gen_random_uuid(), 'test.event',
                       jsonb_build_object('id', x.id::text, 'type', 'test.event', 'data', jsonb_build_object('n', x.n)),
                       'PENDING', 0, now() - interval '1 second', now()
                  FROM (SELECT gen_random_uuid() AS id, g AS n FROM generate_series(1, ?) g) x
                RETURNING id
                """, UUID.class, hooked, n);
    }
}
