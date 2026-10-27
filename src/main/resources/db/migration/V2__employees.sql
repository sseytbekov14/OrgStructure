-- Employees synchronized from Active Directory (spec v2.1 §6.1). AD is the source of these facts; the sync
-- upserts by ad_guid (objectGUID) and never deletes: leavers get enabled = false.

CREATE TABLE employees (
    id                BIGSERIAL     PRIMARY KEY,
    ad_guid           UUID          NOT NULL UNIQUE,
    dn                VARCHAR(512)  NOT NULL,
    login             VARCHAR(128)  NOT NULL,
    upn               VARCHAR(256)  NOT NULL,
    email             VARCHAR(256),
    employee_id       VARCHAR(64),
    display_name      VARCHAR(256)  NOT NULL,
    first_name        VARCHAR(128),
    last_name         VARCHAR(128),
    job_title         VARCHAR(256),
    company           VARCHAR(256),
    department        VARCHAR(512),           -- raw AD value
    dept_level1       VARCHAR(256),
    dept_level2       VARCHAR(256),
    dept_level3       VARCHAR(256),
    dept_level4       VARCHAR(256),
    manager_dn        VARCHAR(512),
    pm_upn            VARCHAR(256),
    manager_id        BIGINT REFERENCES employees(id) ON DELETE SET NULL,
    pm_id             BIGINT REFERENCES employees(id) ON DELETE SET NULL,
    country_code      VARCHAR(8),
    office            VARCHAR(256),
    city              VARCHAR(128),
    phone             VARCHAR(64),
    mobile            VARCHAR(64),
    enabled           BOOLEAN       NOT NULL DEFAULT TRUE,
    is_external       BOOLEAN       NOT NULL DEFAULT FALSE,  -- manager/PM outside Central Services
    ad_when_changed   TIMESTAMPTZ,
    last_synced_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_employees_dn_lower    ON employees (lower(dn));
CREATE UNIQUE INDEX ux_employees_upn_lower   ON employees (lower(upn));
CREATE INDEX        ix_employees_email_lower ON employees (lower(email));
CREATE INDEX        ix_employees_manager_id  ON employees (manager_id);
CREATE INDEX        ix_employees_pm_id       ON employees (pm_id);
CREATE INDEX        ix_employees_dept        ON employees (dept_level1, dept_level2, dept_level3, dept_level4);
CREATE INDEX        ix_employees_country     ON employees (country_code);
CREATE INDEX        ix_employees_enabled     ON employees (enabled);
