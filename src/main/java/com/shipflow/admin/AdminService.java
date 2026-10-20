package com.shipflow.admin;

import com.shipflow.carrier.CarrierDirectory;
import com.shipflow.carrier.capacity.CapacityRepository;
import com.shipflow.carrier.port.CarrierPortRegistry;
import com.shipflow.common.error.ErrorCode;
import com.shipflow.common.error.ShipFlowException;
import com.shipflow.security.HmacSigner;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Master-data administration (merchants, carriers & rate cards, capacity overrides). */
@Service
public class AdminService {
    private final JdbcTemplate jdbc;
    private final CarrierDirectory carriers;
    private final CarrierPortRegistry ports;
    private final CapacityRepository capacity;

    public AdminService(JdbcTemplate jdbc, CarrierDirectory carriers, CarrierPortRegistry ports,
                        CapacityRepository capacity) {
        this.jdbc = jdbc;
        this.carriers = carriers;
        this.ports = ports;
        this.capacity = capacity;
    }

    /** Returns the merchant id and its webhook signing secret (shown once). */
    @Transactional
    public Map<String, Object> createMerchant(AdminRequests.CreateMerchant r) {
        UUID id = UUID.randomUUID();
        String secret = HmacSigner.newSecret();
        jdbc.update("INSERT INTO merchant (id, name, country_code, webhook_url, webhook_secret) VALUES (?, ?, ?, ?, ?)",
                id, r.name(), r.countryCode(), r.webhookUrl(), secret);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", r.name());
        m.put("webhookUrl", r.webhookUrl());
        m.put("webhookSecret", secret);
        return m;
    }

    @Transactional
    public Map<String, Object> createCarrier(AdminRequests.CreateCarrier r) {
        if (!ports.supports(r.integration())) {
            throw ShipFlowException.unprocessable("no CarrierPort adapter for integration " + r.integration());
        }
        String secret = HmacSigner.newSecret();
        long id;
        try {
            id = jdbc.queryForObject("""
                    INSERT INTO carrier (code, name, kind, integration, inbound_webhook_secret) VALUES (?, ?, ?, ?, ?)
                    RETURNING id
                    """, Long.class, r.code(), r.name(), r.kind(), r.integration(), secret);
        } catch (DuplicateKeyException e) {
            throw new ShipFlowException(ErrorCode.ALREADY_EXISTS, "carrier " + r.code() + " already exists");
        }
        for (var z : r.zones()) {
            jdbc.update("""
                    INSERT INTO carrier_zone (carrier_id, zone_code, sla_days, cod_supported, max_weight_g, currency,
                                              extra_per_500g, cod_fee_percent, cod_fee_min, default_daily_capacity)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, id, z.zoneCode(), z.slaDays(), z.codSupported(), z.maxWeightGrams(), z.currency(),
                    z.extraPer500g(), z.codFeePercent(), z.codFeeMin(), z.defaultDailyCapacity());
            for (var s : z.slabs()) {
                jdbc.update("INSERT INTO rate_slab (carrier_id, zone_code, up_to_g, price) VALUES (?, ?, ?, ?)",
                        id, z.zoneCode(), s.upToGrams(), s.price());
            }
        }
        carriers.invalidate();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("code", r.code());
        m.put("inboundWebhookSecret", secret);
        return m;
    }

    @Transactional
    public void setCapacity(String carrierCode, AdminRequests.SetCapacity r) {
        var c = carriers.requireCode(carrierCode);
        capacity.setCapacity(c.id(), r.zoneCode(), r.date(), r.capacity());
    }
}
