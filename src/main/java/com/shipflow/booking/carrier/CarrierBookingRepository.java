package com.shipflow.booking.carrier;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
public class CarrierBookingRepository {
    public record Booking(long id, UUID shipmentId, long carrierId, String bookingRef, String status, String awb,
                          int attempts) {
    }

    private final JdbcTemplate jdbc;

    public CarrierBookingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Claims the right to call the carrier for (shipment, carrier): creates the booking row or re-arms a
     * PENDING/UNKNOWN/FAILED one whose lease expired. Returns empty if someone else holds a live lease or the
     * booking is already CONFIRMED/CANCELLED. The booking_ref is stable across retries (carrier-side dedupe).
     */
    public Optional<Long> claim(UUID shipmentId, long carrierId, String ref, Instant now, Instant leaseUntil) {
        return jdbc.queryForList("""
                INSERT INTO carrier_booking (shipment_id, carrier_id, booking_ref, status, attempts, lease_until, created_at, updated_at)
                VALUES (?, ?, ?, 'PENDING', 1, ?, ?, ?)
                ON CONFLICT (shipment_id, carrier_id) DO UPDATE
                   SET status = 'PENDING', attempts = carrier_booking.attempts + 1,
                       lease_until = EXCLUDED.lease_until, updated_at = EXCLUDED.updated_at, last_error = NULL
                 WHERE carrier_booking.status IN ('PENDING','UNKNOWN','FAILED')
                   AND (carrier_booking.lease_until IS NULL OR carrier_booking.lease_until < EXCLUDED.updated_at)
                RETURNING id
                """, Long.class, shipmentId, carrierId, ref, Timestamp.from(leaseUntil), Timestamp.from(now),
                Timestamp.from(now)).stream().findFirst();
    }

    public boolean confirm(long id, String awb, Instant now) {
        return jdbc.update("""
                UPDATE carrier_booking SET status = 'CONFIRMED', awb = ?, lease_until = NULL, updated_at = ?, last_error = NULL
                 WHERE id = ? AND status IN ('PENDING','UNKNOWN')
                """, awb, Timestamp.from(now), id) == 1;
    }

    public boolean fail(long id, String error, Instant now) {
        return jdbc.update("""
                UPDATE carrier_booking SET status = 'FAILED', lease_until = NULL, updated_at = ?, last_error = ?
                 WHERE id = ? AND status IN ('PENDING','UNKNOWN')
                """, Timestamp.from(now), trunc(error), id) == 1;
    }

    public boolean markUnknown(long id, String error, Instant now) {
        return jdbc.update("""
                UPDATE carrier_booking SET status = 'UNKNOWN', lease_until = NULL, updated_at = ?, last_error = ?
                 WHERE id = ? AND status = 'PENDING'
                """, Timestamp.from(now), trunc(error), id) == 1;
    }

    /** Cancels the shipment's active booking; returns it so the carrier can be told after commit. */
    public Optional<Booking> cancelActive(UUID shipmentId, Instant now) {
        return jdbc.query("""
                UPDATE carrier_booking SET status = 'CANCELLED', lease_until = NULL, updated_at = ?
                 WHERE shipment_id = ? AND status IN ('PENDING','CONFIRMED','UNKNOWN')
                RETURNING id, shipment_id, carrier_id, booking_ref, status, awb, attempts
                """, this::map, Timestamp.from(now), shipmentId).stream().findFirst();
    }

    public Optional<Booking> active(UUID shipmentId) {
        return jdbc.query("""
                SELECT id, shipment_id, carrier_id, booking_ref, status, awb, attempts FROM carrier_booking
                 WHERE shipment_id = ? AND status IN ('PENDING','CONFIRMED','UNKNOWN')
                """, this::map, shipmentId).stream().findFirst();
    }

    public List<Booking> byShipment(UUID shipmentId) {
        return jdbc.query("""
                SELECT id, shipment_id, carrier_id, booking_ref, status, awb, attempts FROM carrier_booking
                 WHERE shipment_id = ? ORDER BY id
                """, this::map, shipmentId);
    }

    public Set<Long> failedCarriers(UUID shipmentId) {
        return jdbc.queryForList("SELECT carrier_id FROM carrier_booking WHERE shipment_id = ? AND status = 'FAILED'",
                Long.class, shipmentId).stream().collect(Collectors.toSet());
    }

    /** Bookings whose outcome is unknown (timeouts) or abandoned (crash mid-call: PENDING with expired lease). */
    public List<Booking> uncertain(Instant olderThan, Instant now, int limit) {
        return jdbc.query("""
                SELECT id, shipment_id, carrier_id, booking_ref, status, awb, attempts FROM carrier_booking
                 WHERE status IN ('PENDING','UNKNOWN') AND updated_at < ?
                   AND (lease_until IS NULL OR lease_until < ?)
                 ORDER BY updated_at LIMIT ?
                """, this::map, Timestamp.from(olderThan), Timestamp.from(now), limit);
    }

    /** ALLOCATED shipments that never got a booking attempt (e.g. crash between commit and carrier call). */
    public List<UUID> strandedAllocated(Instant olderThan, int limit) {
        return jdbc.queryForList("""
                SELECT s.id FROM shipment s
                 WHERE s.status = 'ALLOCATED' AND s.updated_at < ? AND s.carrier_awb IS NULL
                   AND NOT EXISTS (SELECT 1 FROM carrier_booking b WHERE b.shipment_id = s.id
                                     AND b.status IN ('PENDING','CONFIRMED','UNKNOWN'))
                 ORDER BY s.updated_at LIMIT ?
                """, UUID.class, Timestamp.from(olderThan), limit);
    }

    private Booking map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Booking(rs.getLong(1), rs.getObject(2, UUID.class), rs.getLong(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getInt(7));
    }

    private static String trunc(String s) {
        return s == null || s.length() <= 500 ? s : s.substring(0, 500);
    }
}
