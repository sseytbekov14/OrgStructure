# RUN INSTRUCTIONS FOR CLAUDE CODE (follow exactly, autonomously, from start to finish)

You are running on a different laptop than the original development machine. Docker is NOT available: never run
docker commands. Use PowerShell syntax. Shell sessions may not persist between commands, so set the environment
variables inline in every command that needs them.

## CONSTANTS

DB_HOST=localhost  DB_PORT=55432  DB_NAME=orgchart_test  DB_USER=postgres  DB_PASSWORD=test

PG_BIN = the environment variable PG_BIN if it is set (folder with psql, pg_ctl, initdb); otherwise search
C:\pg15 and C:\Program Files\PostgreSQL for pg_ctl.exe; if not found, stop.

Databases: orgchart_test (test suite), orgchart_legacy (schema of the old version for upgrade tests), orgchart_view
(running the application and optional browser checks). Use ONLY these three databases on port 55432; never touch any
other database or port (another PostgreSQL service may run on 5432: leave it alone). "Fresh database" = DROP DATABASE
and CREATE DATABASE with psql.

## STEP E - ENVIRONMENT (do first; fix what you can; stop with exact instructions for what you cannot)

1. Project root: pom.xml, mvnw.cmd, src/ and docs/ exist. Git: branch is feature/ad-sync; git status shows only
   untracked bin/ and docs/. If git reports "dubious ownership", run git config --global --add safe.directory for this
   folder. If status lists many modified files caused only by line endings, run git config core.autocrlf true in this
   repo and re-check. If there are real unexpected modifications, stop and report. git config user.name and user.email
   must be set, otherwise stop and tell me. The history must contain the commits "AD: configurable LDAP timeouts via
   env" and "Use example domain names in a comment and a unit test"; if not, stop and report.
2. docs/OrgChart_Admin_UX_Release_Prompts.md, docs/OrgChart_AD_Integration_Spec_v3.md and docs/PROJECT_ANALYSIS.md exist.
   There must be no other spec version in docs/.
3. Tools: java -version is 21; node -v is 18 or newer; mvnw.cmd -v works. If something is missing, stop and tell me what
   to install.
4. PostgreSQL: if C:\pg15\data does not exist run: initdb -D C:\pg15\data -U postgres -A trust -E UTF8 --locale=C
   (from PG_BIN). If it is not running on port 55432 start it: pg_ctl -D C:\pg15\data -o "-p 55432" -l C:\pg15\pg.log start
   (never as Administrator). Create the three databases if missing. select version() must work. The server version may be
   newer than 15 (for example 18): that is acceptable; Flyway may print a "not tested" warning.
5. Add .claude/, logs/ and *.bundle to .git/info/exclude (not to .gitignore).
6. Baseline: full build and all tests with the DB_* constants. Record the test count in the progress file. If the baseline
   fails, stop and report; do not change code to fix it. If failures are clearly caused only by the PostgreSQL server
   version (not by code), say so explicitly in the report.

When running the application itself (spring-boot:run) or browser checks use orgchart_view with EMPLOYEE_SOURCE=db,
AD_SYNC_ENABLED=true, AD_SYNC_MODE=mock, AD_SYNC_ON_STARTUP=true, AD_SYNC_CRON=-, AUTH_PASSWORD_ENABLED=true,
SYSTEM_ADMIN_EMAILS=dev, OVERRIDES_IMPORT_FROM_EXCEL=false. Always stop every application or browser process you start.
Leave PostgreSQL running and orgchart_view filled with the mock data at the end.

## MAIN TASK

Read docs/OrgChart_Admin_UX_Release_Prompts.md, docs/OrgChart_AD_Integration_Spec_v3.md and docs/PROJECT_ANALYSIS.md fully.
Execute the steps U0, U1, U2, U3, U4, U5, U6, U7 in this order, in this single session, completely and autonomously.
Do not ask me questions: where something is ambiguous, apply the decisions in section 1 of the prompts file and write the
choice into the progress file.

### Setup

- Branch feature/ad-sync. Create a local safety tag first: git tag backup/before-admin-ux (skip if it exists).
- Keep a progress file docs/PROGRESS_ADMIN_UX.md (it stays untracked): per step the status
  (TODO / IN PROGRESS / DONE / BLOCKED), commit hash, decisions and assumptions, bugs found and fixed, open issues.
  Update it after every step and after every significant decision. At the start of each step and after any context
  compaction, re-read the progress file and that step's section of the prompts file. If the progress file already exists,
  resume from the first step that is not DONE (re-run Step E first, it is idempotent); never redo finished steps.
- The common rules in section 2 of the prompts file apply to every step, together with section 17.0 of the spec. Wherever
  they say "throwaway PostgreSQL container" use the local PostgreSQL described above.
- Use subagents for read-only audits and analysis if that keeps your context small.

### Step U0 (not in the prompts file): Phase 5d, country head detection without the title suffix

Context: in the real AD the job titles do not carry the ", XX" country suffix that the Excel titles have (53 of 58 rows
differ: UZ 21, AZ 17, GE 9, AM 6). The frontend detects country heads with getCountryHeadCode() (group HEAD + title
suffix); in db mode those heads would no longer be recognized.
1. If Phase 5d is already committed (git log), mark U0 DONE and continue.
2. Read-only analysis (findings go into the progress file): every use of the ", XX" suffix, getCountryHeadCode() and
   COUNTRY_CODES in the frontend and in Java, and what the user sees when the suffix is missing.
3. Minimal fix in db mode only (excel mode stays identical): country head = group HEAD AND (suffix present OR the DTO
   countryCode is a non-KZ code from COUNTRY_CODES); the suffix wins when present; job titles in the DTO are not changed.
4. Tests: detection matrix (suffix only, countryCode only, both, a KZ head is not a country head, a non-head with a non-KZ
   country is not a country head), excel mode unchanged, render test with the mock data.
5. Commit: "Phase 5d: country head detection without title suffix".

### Steps U1 to U7

Implement exactly the "Task Un" prompt in section 3 of the prompts file, with its tests and its commit message. One commit
per step. Section 4 (manual checklist) is for the final report.

### Quality gates for every step

- A step is finished only when: the full build and all tests are green (SSO tests included), git diff shows no changes in
  the protected files (SAML, SecurityConfig, SamlConfig, security/, SSO tests, config/ tests, application-*.yml security
  settings), and git grep -n -i -E "kworld|KZALMGC01|10\.207|ccg-svc" -- src shows only the CanonicalHostFilterTest line.
  Then commit and mark the step DONE.
- If a test fails, fix it and rerun (up to 3 different fix attempts per failure). If it still fails, or if a step would
  require touching a protected file, stop: mark the step BLOCKED in the progress file, do not start the next step, and give
  me a clear report.
- Never weaken or delete existing tests to make them pass. Tests that assert behaviour this release deliberately changes
  (paths /api/admin -> /api/manage, "Direct reports" wording, removed copy button) are updated; list each of them in the
  progress file.
- Never push. Never rewrite history. Stage only files of the current step.
- U2: check whether the official logo file src/main/resources/static/images/kpmg-logo.svg (or .png) exists; if it does not,
  build the placeholder and carry on, and list it under "what I must provide" in the final report.

## FINAL (after U7)

1. Run the complete test suite on a fresh orgchart_test database and on orgchart_legacy migrated from the pre-U1 schema
   (the state at the tag backup/before-admin-ux, via a git worktree).
2. Create a transfer bundle of the new commits one folder above the project:
   git bundle create ../orgchart-ad-ux.bundle backup/before-admin-ux..feature/ad-sync
   and verify it with git bundle verify.
3. Final report: a table per step (commit hash, test count), every new or changed endpoint (method, path, who may call it),
   every migration, every new config key, deviations from the prompts file, known limitations, the manual checklist from
   section 4 of the prompts file, the exact PowerShell commands to start the application against orgchart_view, and
   everything I must provide or decide.
