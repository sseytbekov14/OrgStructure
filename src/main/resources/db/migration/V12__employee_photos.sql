-- Employee photos from AD thumbnailPhoto (docs: photos task). One row per checked employee: PRESENT (the validated,
-- re-encoded JPEG), NONE (AD has no photo: no bytes, the row stops re-reads until whenChanged changes) or INVALID
-- (AD has a value that is not a usable image: no bytes). Served only through GET /api/photos/{employeeId}.

CREATE TABLE employee_photos (
    employee_id          BIGINT       PRIMARY KEY REFERENCES employees (id) ON DELETE CASCADE,
    state                VARCHAR(16)  NOT NULL,
    content              BYTEA,
    content_type         VARCHAR(32),
    width                INT,
    height               INT,
    size_bytes           INT,
    sha256               CHAR(64),
    -- employees.ad_when_changed when the photo was checked: a different value makes the employee a candidate again
    source_when_changed  TIMESTAMPTZ,
    checked_at           TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_employee_photos_state CHECK (state IN ('PRESENT', 'NONE', 'INVALID')),
    CONSTRAINT ck_employee_photos_content CHECK (
        (state = 'PRESENT' AND content IS NOT NULL AND sha256 IS NOT NULL)
        OR (state <> 'PRESENT' AND content IS NULL))
);

-- The photo phase of a sync run (counts only)
ALTER TABLE ad_sync_log
    ADD COLUMN photos_fetched INT,
    ADD COLUMN photos_updated INT,
    ADD COLUMN photos_removed INT,
    ADD COLUMN photos_invalid INT,
    ADD COLUMN photos_failed  INT;
