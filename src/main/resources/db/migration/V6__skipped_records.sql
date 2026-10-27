-- Spec v3 §4.4, §6.1, §6.2: AD records that cannot be stored are skipped and counted, and a synced employee always
-- has an allowed country (only external managers/PMs, which may live outside OU=Countries, have none).

ALTER TABLE ad_sync_log ADD COLUMN skipped_count INT;  -- records skipped by the §4.4 rules

ALTER TABLE employees ADD CONSTRAINT ck_employees_country CHECK (is_external OR country_code IS NOT NULL);
