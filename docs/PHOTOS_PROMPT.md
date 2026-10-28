# Employee photos from Active Directory — standalone instructions for Claude Code

This file is self-contained. Follow it exactly and autonomously from start to finish. Do not ask questions: where something
is ambiguous, apply the decisions below and write the choice into docs/PROGRESS_PHOTOS.md (untracked).

## 0. Environment and rules

- Project: Spring Boot 3.5 / Java 21 / PostgreSQL / Flyway, plain JS frontend in src/main/resources/static. Work on the
  current branch (feature/ad-sync). Local commits only: never push, never rebase, never rewrite history. Stage only the files
  of this task (no `git add -A`). Keep docs/ and docs-share/ out of the commit.
- Shell: PowerShell syntax. Sessions may not persist, so set environment variables inline in every command that needs them.
- Database for tests: use the PostgreSQL described by the environment variables DB_HOST, DB_PORT, DB_NAME, DB_USER, DB_PASSWORD
  (a throwaway database only; never a database that contains real data). If they are not set: if Docker exists start a
  throwaway postgres:15 container; otherwise look for a local throwaway PostgreSQL on localhost:55432 (user postgres, databases
  orgchart_test / orgchart_legacy / orgchart_view; PG_BIN may point to its bin folder; start it with
  pg_ctl -D C:\pg15\data -o "-p 55432" -l C:\pg15\pg.log start if it is not running); if none is available, stop and tell me.
  Use only throwaway databases. Always stop every process you start.
- Do NOT modify SAML, SecurityConfig, SamlConfig, security/ classes, SSO tests, config/ tests, or the security settings in
  application-*.yml. If this task seems to require it, stop and report.
- Flyway: new migration with the next free version number; never edit a committed migration.
- Never weaken or delete existing tests. Update only tests that assert something this task deliberately changes, and list them.
- UI language English. No alert/confirm/prompt, no external fonts/CDNs, no inline event handlers (CSP), no new innerHTML (use
  textContent / DOM APIs; StoredXssTest pins innerHTML counts: review before changing a pin).
- Never log photo bytes, names or e-mails: ids, states and counts only.
- Environment variable FORBIDDEN_PATTERN (a regex of internal names) may be set: after the change
  `git grep -n -i -E "$env:FORBIDDEN_PATTERN" -- src` must show nothing new (only the existing CanonicalHostFilterTest line).
  If the variable is not set, say so in the progress file and skip this check.

Start: confirm git status is clean, run the full build and tests, record the baseline test count. If the baseline fails, stop
and report.

## 1. Context

- AD attribute `thumbnailPhoto`: binary, single-valued, normally JPEG, at most about 100 KB, often about 96x96 px. The same
  bind account that reads the other attributes reads it.
- Today the site shows photos from the folder `photos/` (file name = e-mail), resolved by `EmployeePhotos`, exposed as `photoUrl`
  in the DTO and served by the existing `/photos/**` resource handler (`FileWatcherService` watches the folder).
- The code base already has: AD sync with an `AdDirectoryClient` (LDAP + mock), `EmployeesSynced` event, `DbEmployeeDirectory`
  (rebuild + SSE), `EmployeeDtoAssembler`, `AuthCircuitBreaker`, `ad_sync_log`. If other features exist (hidden employees table,
  unified card component, /api/manage admin endpoints), integrate with them as described; if one is missing, skip that
  integration and note it in the progress file.
- Goal: in db mode show the photo from AD; keep the folder as a fallback; keep a clean initials placeholder as the last resort.
  Excel mode stays exactly as it is.

## 2. Decisions (already taken)

| # | Decision |
|---|---|
| P1 | Feature flag `AD_PHOTOS_ENABLED`, default **false** (extra AD reads and the information-security approval for storing photos are pending). Works only with `EMPLOYEE_SOURCE=db`. |
| P2 | `PHOTO_SOURCE_PRIORITY=ad,folder` (default): AD photo, then folder photo, then initials. `folder,ad` is allowed. |
| P3 | Photos are stored in the database and served only through an authenticated endpoint, never through a public static path. |
| P4 | Bytes from AD are **untrusted** (users may be able to write this attribute): validate and re-encode; never serve them as received. |
| P5 | Only enabled, non-external employees get photos. The photo is purged when the employee is disabled. Hidden employees are never served. External managers get no photo. |
| P6 | The photo phase must never fail, slow down or change the counts of the main employee sync. It is incremental. |

## 3. Tasks

1. **Migration.** Table `employee_photos`: employee_id BIGINT PK FK employees ON DELETE CASCADE, state VARCHAR(16) NOT NULL
   (PRESENT / NONE / INVALID), content BYTEA NULL, content_type VARCHAR(32), width INT, height INT, size_bytes INT,
   sha256 CHAR(64), source_when_changed TIMESTAMPTZ (the employee's `ad_when_changed` at the time of the check),
   checked_at TIMESTAMPTZ NOT NULL. `ad_sync_log` gets photos_fetched, photos_updated, photos_removed, photos_invalid,
   photos_failed (INT, nullable). Entities; the BYTEA column must never be loaded by list queries (separate entity or projection).
2. **LDAP.** Add `thumbnailPhoto` to `java.naming.ldap.attributes.binary` (next to objectGUID); otherwise JNDI decodes the value
   as a string and corrupts it. The main paged search does NOT request the photo. Add to the directory client a read of one
   employee's `thumbnailPhoto` only: base-scope read by DN requesting just that attribute; if the DN no longer exists, fall back
   to a search by objectGUID (escape the binary GUID in the filter). The mock client serves synthetic photos (task 8).
3. **PhotoValidator** (pure class). Accept JPEG and PNG only (check magic bytes, ignore any declared type). Reject empty values,
   more than `AD_PHOTOS_MAX_BYTES` (default 204800), and images whose declared width or height exceeds `AD_PHOTOS_MAX_SIDE`
   (default 1024) or whose pixel count exceeds 1,000,000, checked through ImageIO reader metadata BEFORE decoding
   (decompression-bomb protection). Decode with ImageIO and re-encode as a clean JPEG (quality about 0.85, white background for
   transparency): this removes EXIF/ICC/trailing data and defeats polyglot files. If the image cannot be decoded (for example
   CMYK), the state is INVALID with a WARN containing the employee id and the reason only. Output: bytes, type image/jpeg,
   width, height, sha256 of the re-encoded bytes.
4. **PhotoSyncService.** Runs after each successful employee sync commit (listener on `EmployeesSynced`), on a single background
   thread, guarded by its own advisory lock so runs never overlap, only when AD_PHOTOS_ENABLED=true and the circuit breaker is not
   suspended (an AUTH failure suspends it exactly as for the main sync).
   - Candidates: enabled, non-external employees with no photo row, or whose `ad_when_changed` differs from
     `source_when_changed`. Everyone else is skipped (later runs stay cheap).
   - Per employee: read, validate, then write the row in its own small transaction: PRESENT (store), NONE (AD has no photo:
     delete stored bytes, keep the row so it is not re-read until whenChanged changes), INVALID. Replacing or removing a photo
     counts as updated / removed.
   - Time budget `AD_PHOTOS_TIME_BUDGET` (default 120s) per run; the rest continues in the next run. A failure on one employee
     is counted in photos_failed and retried next run; it never aborts the run.
   - Purge the photo bytes of employees that became disabled.
   - Write the counters into the sync log row of that run. After a run that changed anything publish a `PhotosChanged` event;
     a listener rebuilds the directory and sends SSE org-updated (use the existing mechanism).
   - `POST /api/admin/ad-sync/photos[?force=true]` for env super admins only (existing /api/admin rule; do not touch
     SecurityConfig): runs the photo phase now; `force=true` ignores the whenChanged comparison.
   - The dry-run report gets `photoCandidates` (how many employees would be read) without reading any photo.
5. **Endpoint** `GET /api/photos/{employeeId}`: same access as the other /api/** endpoints (signed-in users; add no security
   rules). 200 with the stored JPEG and headers `Content-Type: image/jpeg`, `X-Content-Type-Options: nosniff`,
   `Content-Disposition: inline`, `ETag` = sha256, `Cache-Control: private, max-age=86400`; 304 on a matching If-None-Match.
   404 when there is no PRESENT photo, the employee is hidden, disabled or external, or the feature is off. No listing or batch
   access.
6. **DTO.** `photoUrl` keeps its meaning (a URL). In db mode with the feature on, for an employee with a PRESENT photo it is
   `/api/photos/{id}?v={first 8 chars of sha256}` (the version parameter busts the cache); the order AD / folder / default follows
   PHOTO_SOURCE_PRIORITY. The assembler gets the photo hashes from one query that does not load bytes, built once per directory
   rebuild. Excel mode and the flag-off case are byte-identical to today.
7. **Frontend** (minimal; reuse the existing tokens and card component if present). Every place that shows a photo (tree nodes,
   groups, search results, profile dialog, counselee lists) uses one shared avatar helper: `<img>` with explicit width and height
   (no layout shift), `loading="lazy"`, `object-fit: cover`, round crop, alt = the person's name only where the photo is the sole
   identification, otherwise empty alt; on load error (addEventListener, never inline handlers) replace the image with an initials
   placeholder using a deterministic color. Do not upscale a 96 px photo beyond its natural size (cap the profile photo at 96 CSS
   px). Do not touch the tree layout algorithm.
8. **Mock data.** The mock client serves synthetic photos for about two thirds of the mock people, generated in code with ImageIO
   (colored squares with initials; no real photos anywhere in the repository) and a few deliberately bad values (empty, random
   bytes, a PNG with trailing HTML, an oversized dimension header, a text file). The UI must show a mix of AD photo, folder photo
   and placeholder.
9. **Config** (also in .env.example; defaults in brackets): AD_PHOTOS_ENABLED (false), PHOTO_SOURCE_PRIORITY (ad,folder),
   AD_PHOTOS_MAX_BYTES (204800), AD_PHOTOS_MAX_SIDE (1024), AD_PHOTOS_TIME_BUDGET (120s).

## 4. Tests (all mandatory)

- PhotoValidator: valid JPEG, valid PNG, wrong magic bytes, declared type lies, empty, too large, huge declared dimensions
  rejected without decoding, truncated file, undecodable (CMYK) -> INVALID, polyglot (image + appended HTML/script) comes out
  clean, EXIF removed, transparency handled, output always JPEG with the right sha256.
- In-memory LDAP (UnboundID): a binary thumbnailPhoto round-trips byte for byte through the client (this fails if the
  binary-attribute setting is missing); entry without a photo; DN gone -> fallback by GUID.
- PhotoSyncService with the mock client on PostgreSQL: first run stores PRESENT / NONE / INVALID correctly; second run reads
  nobody; a changed whenChanged re-reads only that employee; a photo removed in AD becomes NONE and the bytes are deleted;
  INVALID never fails the run; a read error counts as failed and is retried; the time budget stops the run and the next run
  continues; a disabled employee's photo is purged; flag off -> no photo reads at all; AUTH failure suspends the photo phase;
  two parallel runs do not overlap; counters in ad_sync_log; the main sync counts are identical with the flag on and off.
- Endpoint (MockMvc): 200 with all headers, 304, 404 for missing / hidden / disabled / external / flag off, 401 for anonymous in
  SSO mode, no way to list photos; the response is never HTML.
- DTO: photoUrl chain for every combination of priority and availability; excel mode unchanged; the list query does not load
  bytes (statement counter or projection test).
- Frontend (node): the avatar helper falls back to initials on error, sets width/height/lazy, uses no inline handlers and no
  innerHTML; StoredXssTest pins reviewed.
- Migration test and FlywayMigrationsTest updated; the whole suite green on a fresh database and on one migrated from the
  previous schema.

## 5. Finish

- Full build and all tests green, protected files untouched, FORBIDDEN_PATTERN check clean.
- Commit: "Employee photos from Active Directory". Never push.
- Final summary: commit hash, test counts, changed files, the migration, the new endpoints and who may call them, new config
  keys, deviations, known limitations, and this manual checklist for a machine with real AD:
  1. set AD_PHOTOS_ENABLED=true, run a real sync, then
     `SELECT state, count(*), round(avg(size_bytes)), max(size_bytes) FROM employee_photos GROUP BY 1;`
  2. check the photo phase duration in the sync log counters and that a second run reads nobody;
  3. open the site in db mode and compare avatars in the tree, groups, search and the profile dialog;
  4. check that `thumbnailPhoto` is readable with the service account too (counts must match the personal account);
  5. storing photos needs the information-security sign-off, like the other copied AD attributes.
