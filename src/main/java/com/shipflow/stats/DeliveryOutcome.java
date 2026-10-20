package com.shipflow.stats;

import java.time.LocalDate;

/**
 * One lifecycle outcome, captured with everything the statistics need so aggregation never re-reads the shipment.
 *
 * @param localDay  outcome date in the destination country's time zone (scorecard bucket)
 * @param leadDays  business days dispatch -> delivered (DELIVERED only; null when unknown)
 */
public record DeliveryOutcome(Kind kind, Long carrierId, Long originHubId, String zoneCode, LocalDate localDay,
                              Integer leadDays, boolean onTime, boolean firstAttempt, String pincode,
                              String phoneHash) {

    public enum Kind { DELIVERED, FAILED, RTO }
}
