-- Network / master data: countries (currency, timezone, weekend), holidays, zones, hubs, pincodes, merchants.
CREATE TABLE country (
    code          varchar(2)  PRIMARY KEY,
    name          varchar(100) NOT NULL,
    currency      varchar(3)  NOT NULL,
    timezone      varchar(64) NOT NULL,
    weekend_days  varchar(64) NOT NULL,           -- comma separated java.time.DayOfWeek names
    CONSTRAINT chk_country_code CHECK (code ~ '^[A-Z]{2}$'),
    CONSTRAINT chk_country_currency CHECK (currency IN ('AED','SAR'))
);

INSERT INTO country (code, name, currency, timezone, weekend_days) VALUES
    ('AE', 'United Arab Emirates', 'AED', 'Asia/Dubai',  'SATURDAY,SUNDAY'),
    ('SA', 'Saudi Arabia',         'SAR', 'Asia/Riyadh', 'FRIDAY,SATURDAY');

CREATE TABLE holiday (
    country_code  varchar(2)   NOT NULL REFERENCES country (code),
    holiday_date  date         NOT NULL,
    name          varchar(100) NOT NULL,
    PRIMARY KEY (country_code, holiday_date)
);

CREATE TABLE zone (
    code          varchar(32)  PRIMARY KEY,
    country_code  varchar(2)   NOT NULL REFERENCES country (code),
    name          varchar(100) NOT NULL
);

CREATE TABLE hub (
    id                 bigserial    PRIMARY KEY,
    code               varchar(32)  NOT NULL UNIQUE,
    name               varchar(100) NOT NULL,
    country_code       varchar(2)   NOT NULL REFERENCES country (code),
    lat                double precision NOT NULL,
    lng                double precision NOT NULL,
    cutoff_local_time  time         NOT NULL,
    active             boolean      NOT NULL DEFAULT true,
    CONSTRAINT chk_hub_lat CHECK (lat BETWEEN -90 AND 90),
    CONSTRAINT chk_hub_lng CHECK (lng BETWEEN -180 AND 180)
);

-- "pincode" = serviceable delivery area code; maps to a pricing/serviceability zone and its last-mile hub.
CREATE TABLE pincode (
    code       varchar(16)  PRIMARY KEY,
    zone_code  varchar(32)  NOT NULL REFERENCES zone (code),
    hub_id     bigint       NOT NULL REFERENCES hub (id),
    city       varchar(100) NOT NULL,
    lat        double precision NOT NULL,
    lng        double precision NOT NULL,
    CONSTRAINT chk_pincode_lat CHECK (lat BETWEEN -90 AND 90),
    CONSTRAINT chk_pincode_lng CHECK (lng BETWEEN -180 AND 180)
);
CREATE INDEX ix_pincode_zone ON pincode (zone_code);
CREATE INDEX ix_pincode_hub ON pincode (hub_id);

CREATE TABLE merchant (
    id              uuid         PRIMARY KEY,
    name            varchar(200) NOT NULL,
    country_code    varchar(2)   NOT NULL REFERENCES country (code),
    webhook_url     varchar(500),
    webhook_secret  varchar(128) NOT NULL,      -- per-merchant HMAC signing secret for outbound webhooks
    created_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT chk_merchant_webhook_url CHECK (webhook_url IS NULL OR webhook_url ~ '^https?://')
);
