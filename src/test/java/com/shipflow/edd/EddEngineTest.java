package com.shipflow.edd;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EddEngineTest {
    private static final ZoneId DUBAI = ZoneId.of("Asia/Dubai");
    private static final BusinessCalendar UAE = new BusinessCalendar(EnumSet.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
            Set.of(LocalDate.of(2026, 12, 2), LocalDate.of(2026, 12, 3))); // National Day
    private static final BusinessCalendar KSA = new BusinessCalendar(EnumSet.of(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY), Set.of());

    private static PromiseDateCalculator.Input in(String bookedLocal, int sla, LeadTimeHistogram lane, BusinessCalendar dest) {
        Instant at = java.time.LocalDateTime.parse(bookedLocal).atZone(DUBAI).toInstant();
        return new PromiseDateCalculator.Input(at, DUBAI, LocalTime.of(14, 0), UAE, dest, sla, lane, 0.8, 30);
    }

    @Test
    void calendarArithmeticRespectsCountryWeekends() {
        LocalDate thu = LocalDate.of(2026, 10, 1);
        assertThat(UAE.plusBusinessDays(thu, 2)).isEqualTo(LocalDate.of(2026, 10, 5)); // Fri, (Sat, Sun), Mon
        assertThat(KSA.plusBusinessDays(thu, 2)).isEqualTo(LocalDate.of(2026, 10, 5)); // (Fri, Sat), Sun, Mon
        assertThat(KSA.isBusinessDay(LocalDate.of(2026, 10, 4))).isTrue();  // Sunday works in KSA
        assertThat(UAE.isBusinessDay(LocalDate.of(2026, 10, 4))).isFalse();
        assertThat(UAE.businessDaysBetween(thu, LocalDate.of(2026, 10, 6))).isEqualTo(3);
        assertThatThrownBy(() -> new BusinessCalendar(EnumSet.allOf(DayOfWeek.class), Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void beforeCutoffDispatchesTodayAfterCutoffNextBusinessDay() {
        var before = PromiseDateCalculator.calculate(in("2026-10-01T13:59", 1, null, UAE)); // Thursday
        assertThat(before.dispatchDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(before.promisedDate()).isEqualTo(LocalDate.of(2026, 10, 2));
        var after = PromiseDateCalculator.calculate(in("2026-10-01T14:00", 1, null, UAE));
        assertThat(after.bookedAfterCutoff()).isTrue();
        assertThat(after.dispatchDate()).isEqualTo(LocalDate.of(2026, 10, 2)); // Friday is a UAE working day
        assertThat(after.promisedDate()).isEqualTo(LocalDate.of(2026, 10, 5)); // skip Sat/Sun
    }

    @Test
    void weekendBookingDispatchesMonday() {
        var r = PromiseDateCalculator.calculate(in("2026-10-03T09:00", 2, null, UAE)); // Saturday
        assertThat(r.dispatchDate()).isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(r.promisedDate()).isEqualTo(LocalDate.of(2026, 10, 7));
    }

    @Test
    void holidaysAreSkipped() {
        var r = PromiseDateCalculator.calculate(in("2026-12-01T10:00", 1, null, UAE)); // Tue, then 2 holidays
        assertThat(r.promisedDate()).isEqualTo(LocalDate.of(2026, 12, 4));
    }

    @Test
    void destinationCalendarDecidesDeliveryDays() {
        // dispatch Thursday from UAE, 1 transit day into KSA: Fri+Sat off => Sunday
        var r = PromiseDateCalculator.calculate(in("2026-10-01T10:00", 1, null, KSA));
        assertThat(r.promisedDate()).isEqualTo(LocalDate.of(2026, 10, 4));
    }

    @Test
    void laneP80OverridesSlaOnlyWithEnoughSamples() {
        LeadTimeHistogram lane = LeadTimeHistogram.of(Map.of(1, 50L, 2, 30L, 4, 20L)); // P80 = 2
        var r = PromiseDateCalculator.calculate(in("2026-10-05T10:00", 1, lane, UAE));
        assertThat(r.transitSource()).isEqualTo("LANE_P80");
        assertThat(r.transitDays()).isEqualTo(2);
        var thin = PromiseDateCalculator.calculate(in("2026-10-05T10:00", 1, LeadTimeHistogram.of(Map.of(5, 10L)), UAE));
        assertThat(thin.transitSource()).isEqualTo("CARRIER_SLA");
        assertThat(thin.transitDays()).isEqualTo(1);
    }

    @Test
    void nearestRankPercentile() {
        LeadTimeHistogram h = LeadTimeHistogram.of(Map.of(1, 8L, 3, 1L, 7, 1L));
        assertThat(h.samples()).isEqualTo(10);
        assertThat(h.percentile(0.8)).hasValue(1);
        assertThat(h.percentile(0.9)).hasValue(3);
        assertThat(h.percentile(1.0)).hasValue(7);
        assertThat(LeadTimeHistogram.empty().percentile(0.5)).isEmpty();
        assertThat(h.add(3, 5).percentile(0.8)).hasValue(3);
        assertThatThrownBy(() -> h.percentile(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
