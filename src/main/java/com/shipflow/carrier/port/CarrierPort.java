package com.shipflow.carrier.port;

import java.util.Optional;

/**
 * Outbound port to a carrier's booking API (one adapter per integration type; carriers reference it by
 * {@code carrier.integration}). Contract for implementations:
 * <ul>
 *   <li>{@link #book} MUST be idempotent on {@link BookingRequest#bookingRef()}: repeating a ref returns the
 *       same AWB and never creates a second consignment;</li>
 *   <li>throw {@link CarrierRejectedException} for definitive business rejections, any other exception for
 *       technical failures (counted by the circuit breaker);</li>
 *   <li>{@link #lookup} lets the reconciler resolve outcomes that were uncertain (timeouts).</li>
 * </ul>
 * Never called while a DB transaction or row lock is held.
 */
public interface CarrierPort {
    String integration();

    BookingResult book(BookingRequest request) throws Exception;

    Optional<BookingResult> lookup(String carrierCode, String bookingRef) throws Exception;

    void cancel(String carrierCode, String bookingRef) throws Exception;
}
