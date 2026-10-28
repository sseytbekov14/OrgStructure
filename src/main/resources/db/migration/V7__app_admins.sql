-- Admin & UX release U1: the people with admin access, managed in the database. The env list SYSTEM_ADMIN_EMAILS
-- stays as the super admins (bootstrap, break-glass, manage this list, run the AD sync); they are not in this table.
-- login_name is the identifier GET /api/me/roles shows as "you" (e-mail/UPN with SSO, account name with the
-- password form), stored as typed and compared case-insensitively.

CREATE TABLE app_admins (
    id          BIGSERIAL    PRIMARY KEY,
    login_name  VARCHAR(256) NOT NULL,
    note        VARCHAR(256),
    added_by    VARCHAR(256),
    added_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE
);

CREATE UNIQUE INDEX ux_app_admins_login_name ON app_admins (lower(login_name));
