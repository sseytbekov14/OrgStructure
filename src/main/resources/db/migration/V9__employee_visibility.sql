-- Admin & UX release U4: people hidden from the site by an admin. Global (not per function); the AD sync never
-- touches this table. Their counselees are shown under the next visible manager.

CREATE TABLE employee_visibility (
    employee_id  BIGINT       PRIMARY KEY REFERENCES employees (id) ON DELETE CASCADE,
    hidden       BOOLEAN      NOT NULL DEFAULT TRUE,
    hidden_by    VARCHAR(256),
    hidden_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    reason       VARCHAR(200)
);
