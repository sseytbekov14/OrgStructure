-- Photos uploaded on the site, by the person themselves or by an admin (source MANUAL). They win over the AD photo,
-- the photo sync never reads or overwrites them, and removing one lets the next sync read the AD photo again.
-- uploaded_by: the name of the signed-in uploader (as updated_by elsewhere).
ALTER TABLE employee_photos
    ADD COLUMN source      VARCHAR(16)  NOT NULL DEFAULT 'AD',
    ADD COLUMN uploaded_by VARCHAR(256);

ALTER TABLE employee_photos
    ADD CONSTRAINT ck_employee_photos_source CHECK (source IN ('AD', 'MANUAL'));

-- An upload is always a usable photo (NONE and INVALID describe what AD has)
ALTER TABLE employee_photos
    ADD CONSTRAINT ck_employee_photos_manual CHECK (source = 'AD' OR state = 'PRESENT');
