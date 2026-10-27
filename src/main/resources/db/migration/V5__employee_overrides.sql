-- Presentation data that AD does not have, edited by admins (spec v2.1 §6.4): one row per person per function.
-- The AD sync never touches this table.

CREATE TABLE employee_overrides (
    id                   BIGSERIAL    PRIMARY KEY,
    employee_id          BIGINT       NOT NULL REFERENCES employees(id) ON DELETE CASCADE,
    cs                   VARCHAR(256) NOT NULL,      -- site function name (F&O, Tenders, ...)
    group_name           VARCHAR(256),               -- team or country code; NULL = default (§5.5)
    head_rank            SMALLINT,                   -- NULL = not a head; 1 = main head (tree root); 2.. = additional heads
    area_of_duties       TEXT,
    job_description      TEXT,
    manager_override_id  BIGINT REFERENCES employees(id) ON DELETE SET NULL,  -- parent for this function only
    version              BIGINT       NOT NULL DEFAULT 0,   -- optimistic locking (@Version)
    updated_by           VARCHAR(256),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ux_overrides_employee_cs UNIQUE (employee_id, cs),
    CONSTRAINT ck_head_rank_positive CHECK (head_rank IS NULL OR head_rank >= 1)
);
CREATE UNIQUE INDEX ux_overrides_cs_head_rank ON employee_overrides (cs, head_rank) WHERE head_rank IS NOT NULL;
CREATE INDEX        ix_overrides_cs           ON employee_overrides (cs);
