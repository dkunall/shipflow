-- Minimal Dubai network for local curl testing (run AFTER the app has started once so Flyway created the schema):
--   psql -h localhost -U postgres -d shipflow -f scripts/seed-dev.sql
-- Secrets below are throwaway local-dev placeholders; generate real ones per merchant/carrier anywhere else.
BEGIN;
INSERT INTO zone (code, country_code, name) VALUES ('DXB-Z1', 'AE', 'Dubai central') ON CONFLICT DO NOTHING;
INSERT INTO hub (code, name, country_code, lat, lng, cutoff_local_time)
VALUES ('DXB-HUB', 'Dubai Al Quoz hub', 'AE', 25.1500, 55.2300, '18:00') ON CONFLICT DO NOTHING;
INSERT INTO pincode (code, zone_code, hub_id, city, lat, lng)
SELECT p.code, 'DXB-Z1', h.id, 'Dubai', p.lat, p.lng
  FROM hub h, (VALUES ('DXB-001', 25.2048, 55.2708), ('DXB-002', 25.0800, 55.1400), ('DXB-003', 25.2285, 55.3273)) AS p(code, lat, lng)
 WHERE h.code = 'DXB-HUB'
ON CONFLICT DO NOTHING;

INSERT INTO merchant (id, name, country_code, webhook_url, webhook_secret)
VALUES ('11111111-1111-1111-1111-111111111111', 'Dev Merchant', 'AE', NULL, 'dev-only-change-me-merchant')
ON CONFLICT DO NOTHING;

-- one own fleet + two simulated 3PLs (SIMULATED_3PL = in-process carrier simulator, latency/fault injectable)
INSERT INTO carrier (code, name, kind, integration, inbound_webhook_secret) VALUES
    ('OWNF',  'Own fleet',        'OWN_FLEET',   'OWN_FLEET',     'dev-only-change-me-ownf'),
    ('FASTX', 'FastX Logistics',  'THIRD_PARTY', 'SIMULATED_3PL', 'dev-only-change-me-fastx'),
    ('CHEAP', 'Cheap Couriers',   'THIRD_PARTY', 'SIMULATED_3PL', 'dev-only-change-me-cheap')
ON CONFLICT DO NOTHING;
INSERT INTO carrier_zone (carrier_id, zone_code, sla_days, cod_supported, max_weight_g, currency, extra_per_500g,
                          cod_fee_percent, cod_fee_min, default_daily_capacity)
SELECT c.id, 'DXB-Z1', v.sla, true, 30000, 'AED', 1.00, 1.50, 2.00, v.cap
  FROM carrier c JOIN (VALUES ('OWNF', 1, 50), ('FASTX', 1, 500), ('CHEAP', 3, 1000)) AS v(code, sla, cap) ON v.code = c.code
ON CONFLICT DO NOTHING;
INSERT INTO rate_slab (carrier_id, zone_code, up_to_g, price)
SELECT c.id, 'DXB-Z1', 5000, v.price
  FROM carrier c JOIN (VALUES ('OWNF', 9.00), ('FASTX', 14.00), ('CHEAP', 11.00)) AS v(code, price) ON v.code = c.code
ON CONFLICT DO NOTHING;

INSERT INTO rider (code, name, hub_id, capacity)
SELECT r.code, r.name, h.id, 40 FROM hub h, (VALUES ('R-01', 'Rider One'), ('R-02', 'Rider Two')) AS r(code, name)
 WHERE h.code = 'DXB-HUB'
ON CONFLICT DO NOTHING;
COMMIT;
