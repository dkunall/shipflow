-- Carriers (own fleet + 3PLs), zone serviceability & commercial terms, weight-slab rate cards, daily capacity.
CREATE TABLE carrier (
    id                      bigserial    PRIMARY KEY,
    code                    varchar(32)  NOT NULL UNIQUE,
    name                    varchar(100) NOT NULL,
    kind                    varchar(16)  NOT NULL,
    integration             varchar(32)  NOT NULL,       -- which CarrierPort adapter talks to it
    active                  boolean      NOT NULL DEFAULT true,
    inbound_webhook_secret  varchar(128) NOT NULL,       -- verifies HMAC of carrier -> ShipFlow scan webhooks
    created_at              timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT chk_carrier_code CHECK (code ~ '^[A-Z0-9_]{2,32}$'),
    CONSTRAINT chk_carrier_kind CHECK (kind IN ('OWN_FLEET','THIRD_PARTY'))
);

CREATE TABLE carrier_zone (
    carrier_id              bigint        NOT NULL REFERENCES carrier (id),
    zone_code               varchar(32)   NOT NULL REFERENCES zone (code),
    sla_days                integer       NOT NULL,
    cod_supported           boolean       NOT NULL,
    max_weight_g            integer       NOT NULL,
    currency                varchar(3)    NOT NULL,
    extra_per_500g          numeric(12,2) NOT NULL DEFAULT 0,
    cod_fee_percent         numeric(5,2)  NOT NULL DEFAULT 0,
    cod_fee_min             numeric(12,2) NOT NULL DEFAULT 0,
    default_daily_capacity  integer       NOT NULL,
    PRIMARY KEY (carrier_id, zone_code),
    CONSTRAINT chk_cz_sla CHECK (sla_days BETWEEN 1 AND 30),
    CONSTRAINT chk_cz_weight CHECK (max_weight_g > 0),
    CONSTRAINT chk_cz_currency CHECK (currency IN ('AED','SAR')),
    CONSTRAINT chk_cz_fees CHECK (extra_per_500g >= 0 AND cod_fee_percent >= 0 AND cod_fee_min >= 0),
    CONSTRAINT chk_cz_capacity CHECK (default_daily_capacity >= 0)
);
-- hot path: candidate lookup by destination zone
CREATE INDEX ix_carrier_zone_zone ON carrier_zone (zone_code);

CREATE TABLE rate_slab (
    carrier_id  bigint        NOT NULL,
    zone_code   varchar(32)   NOT NULL,
    up_to_g     integer       NOT NULL,
    price       numeric(12,2) NOT NULL,
    PRIMARY KEY (carrier_id, zone_code, up_to_g),
    FOREIGN KEY (carrier_id, zone_code) REFERENCES carrier_zone (carrier_id, zone_code) ON DELETE CASCADE,
    CONSTRAINT chk_slab CHECK (up_to_g > 0 AND price >= 0)
);

-- Per carrier/zone/day capacity. Rows are created lazily (from default_daily_capacity) by the atomic
-- reserve upsert or explicitly by ops overrides. The CHECK is the last line of defence against overbooking.
CREATE TABLE carrier_capacity (
    carrier_id    bigint      NOT NULL,
    zone_code     varchar(32) NOT NULL,
    service_date  date        NOT NULL,
    capacity      integer     NOT NULL,
    reserved      integer     NOT NULL DEFAULT 0,
    PRIMARY KEY (carrier_id, zone_code, service_date),
    FOREIGN KEY (carrier_id, zone_code) REFERENCES carrier_zone (carrier_id, zone_code) ON DELETE CASCADE,
    CONSTRAINT chk_capacity_bounds CHECK (capacity >= 0 AND reserved >= 0 AND reserved <= capacity)
);
