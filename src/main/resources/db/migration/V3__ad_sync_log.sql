-- One row per AD sync run (spec v2.1 §6.2, §7).

CREATE TABLE ad_sync_log (
    id               BIGSERIAL    PRIMARY KEY,
    started_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finished_at      TIMESTAMPTZ,
    status           VARCHAR(16)  NOT NULL,  -- RUNNING / SUCCESS / FAILED / SKIPPED
    trigger_type     VARCHAR(16)  NOT NULL,  -- SCHEDULED / MANUAL / STARTUP
    fetched_count    INT,
    inserted_count   INT,
    updated_count    INT,
    disabled_count   INT,
    external_count   INT,
    unmapped_count   INT,
    pm_localpart_count INT,                  -- PMs resolved only by local part of the e-mail
    error_code       VARCHAR(32),            -- e.g. AUTH, TLS, NETWORK, TIMEOUT, SAFETY
    error_message    TEXT
);
