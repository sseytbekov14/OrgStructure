-- Admin & UX release U4: groups are objects of a function. A person has at most one group per function
-- (employee_overrides.group_id); without one the AD default group applies (spec §5.5). AD default groups are
-- registered as AUTO groups by the application (GroupRegistrar); admins add MANUAL groups.
-- name is VARCHAR(256) so that migrated names are never cut (new names: 2-60 characters, FieldRules).

CREATE TABLE function_groups (
    id          BIGSERIAL    PRIMARY KEY,
    cs          VARCHAR(256) NOT NULL,
    name        VARCHAR(256) NOT NULL,
    sort_order  INT          NOT NULL DEFAULT 0,
    kind        VARCHAR(16)  NOT NULL DEFAULT 'TEAM',
    source      VARCHAR(16)  NOT NULL DEFAULT 'MANUAL',
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    version     BIGINT       NOT NULL DEFAULT 0,
    created_by  VARCHAR(256),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by  VARCHAR(256),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_function_groups_kind CHECK (kind IN ('TEAM', 'COUNTRY')),
    CONSTRAINT ck_function_groups_source CHECK (source IN ('AUTO', 'MANUAL'))
);

CREATE UNIQUE INDEX ux_function_groups_cs_name ON function_groups (cs, lower(name));

ALTER TABLE employee_overrides ADD COLUMN group_id BIGINT REFERENCES function_groups (id);
CREATE INDEX ix_overrides_group_id ON employee_overrides (group_id);

-- Existing assignments: one group per distinct (function, group name), then the rows point to it.
-- Both statements change nothing when run a second time. group_name stays (for a rollback to the previous
-- release) but the application reads group_id only.
INSERT INTO function_groups (cs, name, kind, source, sort_order, created_by, updated_by)
SELECT DISTINCT ON (o.cs, lower(btrim(o.group_name)))
       o.cs,
       btrim(o.group_name),
       CASE WHEN upper(btrim(o.group_name)) IN ('KZ', 'UZ', 'AZ', 'GE', 'AM', 'KG', 'RU') THEN 'COUNTRY' ELSE 'TEAM' END,
       'MANUAL',
       0,
       'migration',
       'migration'
FROM employee_overrides o
WHERE o.group_name IS NOT NULL AND btrim(o.group_name) <> ''
ORDER BY o.cs, lower(btrim(o.group_name)), o.id
ON CONFLICT (cs, lower(name)) DO NOTHING;

UPDATE employee_overrides o
SET group_id = g.id
FROM function_groups g
WHERE o.group_id IS NULL
  AND o.group_name IS NOT NULL
  AND g.cs = o.cs
  AND lower(g.name) = lower(btrim(o.group_name));
