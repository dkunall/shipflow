package com.shipflow.edd;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Set;

/** Working-day arithmetic for one country (weekend days differ: UAE Sat-Sun, KSA Fri-Sat) + public holidays. */
public final class BusinessCalendar {
    private static final int MAX_SCAN_DAYS = 366;
    private final Set<DayOfWeek> weekend;
    private final Set<LocalDate> holidays;

    public BusinessCalendar(Set<DayOfWeek> weekend, Set<LocalDate> holidays) {
        if (weekend.size() >= 7) {
            throw new IllegalArgumentException("a calendar needs at least one working day");
        }
        this.weekend = weekend.isEmpty() ? EnumSet.noneOf(DayOfWeek.class) : EnumSet.copyOf(weekend);
        this.holidays = Set.copyOf(holidays);
    }

    public boolean isBusinessDay(LocalDate d) {
        return !weekend.contains(d.getDayOfWeek()) && !holidays.contains(d);
    }

    public LocalDate onOrAfter(LocalDate d) {
        LocalDate x = d;
        for (int i = 0; i < MAX_SCAN_DAYS && !isBusinessDay(x); i++) {
            x = x.plusDays(1);
        }
        if (!isBusinessDay(x)) {
            throw new IllegalStateException("no business day within a year of " + d);
        }
        return x;
    }

    public LocalDate nextAfter(LocalDate d) {
        return onOrAfter(d.plusDays(1));
    }

    /** The date that is {@code n} business days after {@code start} (n = 0 -> start itself). */
    public LocalDate plusBusinessDays(LocalDate start, int n) {
        if (n < 0) {
            throw new IllegalArgumentException("n must be >= 0");
        }
        LocalDate d = start;
        for (int i = 0; i < n; i++) {
            d = nextAfter(d);
        }
        return d;
    }

    /** Number of business days in (from, to]; 0 if to <= from. */
    public int businessDaysBetween(LocalDate from, LocalDate to) {
        int n = 0;
        for (LocalDate d = from.plusDays(1); !d.isAfter(to); d = d.plusDays(1)) {
            if (isBusinessDay(d)) {
                n++;
            }
        }
        return n;
    }
}
