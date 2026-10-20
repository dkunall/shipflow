package com.shipflow.carrier;

import com.shipflow.carrier.domain.CarrierInfo;
import com.shipflow.carrier.domain.CarrierKind;
import com.shipflow.common.error.ShipFlowException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Read-through cache of carrier master data (tiny, rarely changing table read on every scan/booking).
 * Refreshed every 30 s or immediately via {@link #invalidate()} after admin changes.
 */
@Component
public class CarrierDirectory {
    private static final Duration TTL = Duration.ofSeconds(30);

    private record Snapshot(Map<Long, CarrierInfo> byId, Map<String, CarrierInfo> byCode, long loadedAtNanos) {
    }

    private final JdbcTemplate jdbc;
    private volatile Snapshot snapshot;

    public CarrierDirectory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<CarrierInfo> byId(long id) {
        CarrierInfo c = current().byId().get(id);
        if (c == null) {
            c = reload().byId().get(id);
        }
        return Optional.ofNullable(c);
    }

    public Optional<CarrierInfo> byCode(String code) {
        CarrierInfo c = current().byCode().get(code);
        if (c == null) {
            c = reload().byCode().get(code);
        }
        return Optional.ofNullable(c);
    }

    public CarrierInfo require(long id) {
        return byId(id).orElseThrow(() -> ShipFlowException.notFound("carrier", id));
    }

    public CarrierInfo requireCode(String code) {
        return byCode(code).orElseThrow(() -> ShipFlowException.notFound("carrier", code));
    }

    public void invalidate() {
        snapshot = null;
    }

    private Snapshot current() {
        Snapshot s = snapshot;
        if (s == null || System.nanoTime() - s.loadedAtNanos() > TTL.toNanos()) {
            s = reload();
        }
        return s;
    }

    private synchronized Snapshot reload() {
        List<CarrierInfo> all = jdbc.query("SELECT id, code, name, kind, integration, active, inbound_webhook_secret FROM carrier",
                (rs, i) -> new CarrierInfo(rs.getLong(1), rs.getString(2), rs.getString(3),
                        CarrierKind.valueOf(rs.getString(4)), rs.getString(5), rs.getBoolean(6), rs.getString(7)));
        Snapshot s = new Snapshot(all.stream().collect(Collectors.toUnmodifiableMap(CarrierInfo::id, Function.identity())),
                all.stream().collect(Collectors.toUnmodifiableMap(CarrierInfo::code, Function.identity())),
                System.nanoTime());
        snapshot = s;
        return s;
    }
}
