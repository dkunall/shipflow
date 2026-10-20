package com.shipflow.network;

import com.shipflow.edd.BusinessCalendar;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Network master data (countries, calendars, hubs, pincodes). Pincodes/hubs are read from the DB (PK lookups);
 * country calendars (weekend + holidays) are cached for 5 minutes because they are needed on every booking and
 * change a few times a year.
 */
@Component
public class NetworkDirectory {
    private static final Duration CALENDAR_TTL = Duration.ofMinutes(5);

    private record CachedCalendar(BusinessCalendar calendar, long loadedAt) {
    }

    private final JdbcTemplate jdbc;
    private final Map<String, CachedCalendar> calendars = new ConcurrentHashMap<>();
    private final Map<String, CountryInfo> countries = new ConcurrentHashMap<>();

    public NetworkDirectory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<PincodeInfo> pincode(String code) {
        return jdbc.query("""
                SELECT p.code, p.zone_code, p.hub_id, p.city, p.lat, p.lng, z.country_code
                  FROM pincode p JOIN zone z ON z.code = p.zone_code WHERE p.code = ?
                """, (rs, i) -> new PincodeInfo(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getString(4),
                rs.getDouble(5), rs.getDouble(6), rs.getString(7)), code).stream().findFirst();
    }

    public Optional<HubInfo> hub(String code) {
        return hubWhere("h.code = ?", code);
    }

    public Optional<HubInfo> hub(long id) {
        return hubWhere("h.id = ?", id);
    }

    private Optional<HubInfo> hubWhere(String where, Object arg) {
        return jdbc.query("""
                SELECT h.id, h.code, h.name, h.country_code, h.lat, h.lng, h.cutoff_local_time, c.timezone
                  FROM hub h JOIN country c ON c.code = h.country_code WHERE h.active AND\s""" + where,
                (rs, i) -> new HubInfo(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getDouble(5), rs.getDouble(6), rs.getTime(7).toLocalTime(), ZoneId.of(rs.getString(8))),
                arg).stream().findFirst();
    }

    public CountryInfo country(String code) {
        return countries.computeIfAbsent(code, c -> jdbc.queryForObject(
                "SELECT code, currency, timezone FROM country WHERE code = ?",
                (rs, i) -> new CountryInfo(rs.getString(1), rs.getString(2), ZoneId.of(rs.getString(3))), c));
    }

    public BusinessCalendar calendar(String countryCode) {
        CachedCalendar cached = calendars.get(countryCode);
        if (cached != null && System.nanoTime() - cached.loadedAt() < CALENDAR_TTL.toNanos()) {
            return cached.calendar();
        }
        String weekend = jdbc.queryForObject("SELECT weekend_days FROM country WHERE code = ?", String.class, countryCode);
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        Arrays.stream(weekend.split(",")).map(String::trim).filter(s -> !s.isEmpty()).map(DayOfWeek::valueOf)
                .forEach(days::add);
        List<LocalDate> holidays = jdbc.queryForList(
                "SELECT holiday_date FROM holiday WHERE country_code = ?", java.sql.Date.class, countryCode)
                .stream().map(java.sql.Date::toLocalDate).toList();
        BusinessCalendar cal = new BusinessCalendar(days, new HashSet<>(holidays));
        calendars.put(countryCode, new CachedCalendar(cal, System.nanoTime()));
        return cal;
    }

    public void invalidateCalendars() {
        calendars.clear();
    }
}
