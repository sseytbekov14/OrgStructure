-- Admin & UX release U6: the country of a person as AD knows it, for the cards. country_name from the AD attribute
-- co (Country/Region name, trimmed, at most 128 characters), country_iso from c (two letters, upper case, else null).
-- country_code (from the DN) stays for the country whitelist and the country-head detection. The next AD sync fills
-- the new columns for existing rows.

ALTER TABLE employees ADD COLUMN country_name VARCHAR(128);
ALTER TABLE employees ADD COLUMN country_iso  VARCHAR(2);
