package com.shipflow.edd;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.OptionalInt;

/**
 * Pure promised-delivery-date (EDD) engine.
 * <ol>
 *   <li>Dispatch date: booking time in the origin hub's zone; if it is a business day and before the hub
 *       cut-off -> today, otherwise the next business day (origin calendar).</li>
 *   <li>Transit days: the lane's historical lead-time percentile (e.g. P80) when the lane has at least
 *       {@code minSamples} deliveries, otherwise the carrier's contractual SLA.</li>
 *   <li>Promised date = dispatch date + transit business days in the destination calendar.</li>
 * </ol>
 */
public final class PromiseDateCalculator {

    public record Input(Instant bookedAt, ZoneId originZone, LocalTime hubCutoff, BusinessCalendar originCalendar,
                        BusinessCalendar destinationCalendar, int carrierSlaDays, LeadTimeHistogram laneHistory,
                        double percentile, int minSamples) {
    }

    public record Result(LocalDate dispatchDate, LocalDate promisedDate, int transitDays, String transitSource,
                         boolean bookedAfterCutoff, long laneSamples) {
    }

    private PromiseDateCalculator() {
    }

    public static LocalDate dispatchDate(Instant bookedAt, ZoneId originZone, LocalTime cutoff, BusinessCalendar cal) {
        ZonedDateTime local = bookedAt.atZone(originZone);
        LocalDate day = local.toLocalDate();
        boolean beforeCutoff = local.toLocalTime().isBefore(cutoff);
        return cal.isBusinessDay(day) && beforeCutoff ? day : cal.nextAfter(day);
    }

    public static Result calculate(Input in) {
        ZonedDateTime local = in.bookedAt().atZone(in.originZone());
        boolean afterCutoff = !local.toLocalTime().isBefore(in.hubCutoff());
        LocalDate dispatch = dispatchDate(in.bookedAt(), in.originZone(), in.hubCutoff(), in.originCalendar());
        int transit = in.carrierSlaDays();
        String source = "CARRIER_SLA";
        LeadTimeHistogram h = in.laneHistory() == null ? LeadTimeHistogram.empty() : in.laneHistory();
        if (h.samples() >= in.minSamples()) {
            OptionalInt p = h.percentile(in.percentile());
            if (p.isPresent()) {
                transit = Math.max(p.getAsInt(), 0);
                source = "LANE_P" + Math.round(in.percentile() * 100);
            }
        }
        // the destination may not be working on the dispatch day; delivery happens on a destination business day
        LocalDate promised = in.destinationCalendar().plusBusinessDays(
                in.destinationCalendar().onOrAfter(dispatch), transit);
        return new Result(dispatch, promised, transit, source, afterCutoff, h.samples());
    }
}
