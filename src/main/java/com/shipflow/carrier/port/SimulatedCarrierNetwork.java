package com.shipflow.carrier.port;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory stand-in for external 3PL booking APIs (no real carrier is ever called). Behaviour per carrier is
 * configurable at runtime (tests, chaos demo via the admin API):
 * <ul>
 *   <li>OK - books (deduplicated by booking ref, like a real idempotent API);</li>
 *   <li>FAIL - technical error (HTTP 5xx equivalent);</li>
 *   <li>FLAKY - the first attempt for a booking ref fails with a 5xx, the next one books (exercises the retry);</li>
 *   <li>REJECT - business rejection;</li>
 *   <li>TIMEOUT_BOOKED - books but responds slower than our timeout (uncertain outcome; lookup finds it);</li>
 *   <li>TIMEOUT_LOST - times out and does NOT book (lookup finds nothing).</li>
 * </ul>
 */
@Component
public class SimulatedCarrierNetwork {
    public enum Mode { OK, FAIL, FLAKY, REJECT, TIMEOUT_BOOKED, TIMEOUT_LOST }

    public record Behaviour(Mode mode, Duration latency) {
    }

    private final Map<String, Behaviour> behaviour = new ConcurrentHashMap<>();
    private final Map<String, String> bookingsByRef = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> createdPerCarrier = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> callsPerCarrier = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> attemptsPerRef = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();
    private volatile Duration hangDuration = Duration.ofSeconds(5);

    public void configure(String carrierCode, Mode mode, Duration latency) {
        behaviour.put(carrierCode, new Behaviour(mode, latency == null ? Duration.ZERO : latency));
    }

    public void setHangDuration(Duration d) {
        this.hangDuration = d;
    }

    public void reset() {
        behaviour.clear();
        bookingsByRef.clear();
        createdPerCarrier.clear();
        callsPerCarrier.clear();
        attemptsPerRef.clear();
    }

    public Behaviour behaviourOf(String carrierCode) {
        return behaviour.getOrDefault(carrierCode, new Behaviour(Mode.OK, Duration.ZERO));
    }

    BookingResult book(BookingRequest r) throws Exception {
        callsPerCarrier.computeIfAbsent(r.carrierCode(), k -> new AtomicInteger()).incrementAndGet();
        Behaviour b = behaviourOf(r.carrierCode());
        sleep(b.latency());
        switch (b.mode()) {
            case FAIL -> throw new IllegalStateException("simulated 503 from " + r.carrierCode());
            case FLAKY -> {
                if (attemptsPerRef.computeIfAbsent(r.bookingRef(), k -> new AtomicInteger()).incrementAndGet() == 1) {
                    throw new IllegalStateException("simulated transient 503 from " + r.carrierCode());
                }
                return new BookingResult(create(r));
            }
            case REJECT -> throw new CarrierRejectedException("simulated rejection by " + r.carrierCode());
            case TIMEOUT_LOST -> {
                sleep(hangDuration);
                throw new IllegalStateException("simulated connection reset");
            }
            case TIMEOUT_BOOKED -> {
                String awb = create(r);
                sleep(hangDuration);
                return new BookingResult(awb);
            }
            default -> {
                return new BookingResult(create(r));
            }
        }
    }

    private String create(BookingRequest r) {
        return bookingsByRef.computeIfAbsent(r.bookingRef(), ref -> {
            createdPerCarrier.computeIfAbsent(r.carrierCode(), k -> new AtomicInteger()).incrementAndGet();
            return r.carrierCode() + "-" + (100000 + seq.incrementAndGet());
        });
    }

    Optional<BookingResult> lookup(String bookingRef) {
        return Optional.ofNullable(bookingsByRef.get(bookingRef)).map(BookingResult::new);
    }

    void cancel(String bookingRef) {
        bookingsByRef.remove(bookingRef);
    }

    /** consignments actually created at the carrier (dedupe proof for tests) */
    public int consignmentsCreated(String carrierCode) {
        AtomicInteger n = createdPerCarrier.get(carrierCode);
        return n == null ? 0 : n.get();
    }

    public int calls(String carrierCode) {
        AtomicInteger n = callsPerCarrier.get(carrierCode);
        return n == null ? 0 : n.get();
    }

    public boolean hasBooking(String bookingRef) {
        return bookingsByRef.containsKey(bookingRef);
    }

    private static void sleep(Duration d) throws InterruptedException {
        if (d != null && !d.isZero()) {
            Thread.sleep(d.toMillis());
        }
    }
}
