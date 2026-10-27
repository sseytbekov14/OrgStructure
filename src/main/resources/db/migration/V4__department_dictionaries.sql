-- Dictionaries of the department normalizer and of the site functions (spec v2.1 §5.3, §5.5, §6.3).
-- They live in the database so that a new AD spelling can be added without a redeploy.

CREATE TABLE department_aliases (
    id         BIGSERIAL    PRIMARY KEY,
    alias      VARCHAR(256) NOT NULL UNIQUE,   -- lower case
    canonical  VARCHAR(256) NOT NULL
);

CREATE TABLE department_general_nodes (
    id            BIGSERIAL    PRIMARY KEY,
    function_name VARCHAR(256) NOT NULL UNIQUE,
    general_name  VARCHAR(256) NOT NULL
);

CREATE TABLE department_function_map (
    id           BIGSERIAL    PRIMARY KEY,
    dept_level2  VARCHAR(256) NOT NULL,
    dept_level3  VARCHAR(256),             -- NULL = any level 3
    cs_name      VARCHAR(256) NOT NULL,    -- function name shown on the site
    visible      BOOLEAN      NOT NULL DEFAULT TRUE
);
CREATE UNIQUE INDEX ux_dfm ON department_function_map (lower(dept_level2), coalesce(lower(dept_level3), ''));

-- §5.3: synonyms, then identity aliases (real team names, so that they are not reported as unmapped)
INSERT INTO department_aliases (alias, canonical) VALUES
    ('cs',                        'Central Services'),
    ('central services',          'Central Services'),
    ('f&o',                       'Facilities & Operations'),
    ('facilities & operations',   'Facilities & Operations'),
    ('ogc',                       'Office of General Counsel'),
    ('office of general counsel', 'Office of General Counsel'),
    ('office general counsel',    'Office of General Counsel'),
    ('hr admin',                  'HR Administration'),
    ('hr administration',         'HR Administration'),
    ('graduate',                  'Graduate Recruiting'),
    ('graduate recruiting',       'Graduate Recruiting'),
    ('tenders',                   'Proposals & Tenders'),
    ('proposals & tenders',       'Proposals & Tenders'),
    ('accounting general',        'Accounting General'),
    ('brand & hospitality',       'Brand & Hospitality'),
    ('compensation & benefits',   'Compensation & Benefits'),
    ('designers markets',         'Designers Markets'),
    ('it operations',             'IT Operations'),
    ('it pmo',                    'IT PMO'),
    ('l&d',                       'L&D'),
    ('talent acquisition',        'Talent Acquisition');

-- §5.3: the node appended when only the function is left
INSERT INTO department_general_nodes (function_name, general_name) VALUES
    ('ITS',                       'ITS General'),
    ('QRM',                       'QRM General'),
    ('Finance',                   'Finance General'),
    ('Markets',                   'Markets General'),
    ('People',                    'People General'),
    ('Facilities & Operations',   'F&O General'),
    ('Office of General Counsel', 'OGC General'),
    ('Procurement',               'Procurement General'),
    ('Executives',                'Administration Office');

-- §5.5: dept_level2 (and optionally dept_level3) -> function shown on the site. The cs_name values are the keys of
-- the frontend (FUNC_ICONS, FUNCTION_FAQ, TREE_BY_DEFAULT).
INSERT INTO department_function_map (dept_level2, dept_level3, cs_name, visible) VALUES
    ('Facilities & Operations',   'Proposals & Tenders', 'Tenders', TRUE),
    ('Facilities & Operations',   NULL,                  'F&O',     TRUE),
    ('Executives',                NULL,                  'F&O',     TRUE),
    ('Procurement',               NULL,                  'F&O',     TRUE),
    ('Office of General Counsel', NULL,                  'OGC',     TRUE),
    ('People',                    NULL,                  'People',  TRUE),
    ('Finance',                   NULL,                  'Finance', TRUE),
    ('ITS',                       NULL,                  'ITS',     TRUE),
    ('Markets',                   NULL,                  'Markets', TRUE),
    ('QRM',                       NULL,                  'QRM',     TRUE);
