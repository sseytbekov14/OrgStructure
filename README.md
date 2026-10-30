# CS OrgChart

Интерактивная оргструктура Central Services: функции, дерево сотрудников, поиск, карточки с контактами, FAQ функций и
админка для правки данных поверх Active Directory.

## Что умеет приложение

- Главная страница со всеми функциями Central Services и поиском по имени, должности, группе и функции.
- Страница функции: дерево подчинения и вид по группам, руководители функции, руководители стран, FAQ.
- Карточка сотрудника: контакты, руководитель (в том числе вне Central Services), подчинённые, фото, реакции.
- Сотрудники берутся из Active Directory (синхронизация в PostgreSQL) или, для отката, из Excel-файла.
- Админы правят группы, руководителей, тексты, FAQ и фото и скрывают людей; все открытые страницы получают изменения
  сразу (Server-Sent Events).
- Вход через Microsoft Entra ID (SAML 2.0).

## Стек

- Java 21, Spring Boot 3.5 (Web, Security, Data JPA, Actuator), Spring Security SAML 2.0 (OpenSAML)
- PostgreSQL, Flyway (миграции в `src/main/resources/db/migration`)
- Spring LDAP (синхронизация с AD по LDAPS), Apache POI (Excel)
- HTML, CSS, Vanilla JavaScript без сборки и внешних CDN

## Структура проекта

```text
src/main/java/cs_orgchart/
|-- access/      админы сайта (app_admins), страница /admin.html
|-- ad/          синхронизация с AD: клиент LDAP / mock, маппинг, планировщик, журнал, health
|-- config/      Spring Security (SSO, CSP, CSRF), SAML, MVC, проверки при старте
|-- controller/  API сотрудников, SSE-поток, реакции и аналитика
|-- department/  словари отделов -> функции
|-- employee/    справочник сотрудников (Excel или БД), группы, правки админов, скрытие
|-- faq/         FAQ функций
|-- importer/    разовый импорт правок из Excel
|-- photo/       фото из AD и загруженные на сайте
|-- security/    режимы входа, роли, допуск SSO
`-- service/     чтение Excel, наблюдение за файлом, SSE, аналитика
src/main/resources/
|-- application.yaml, application-{local,stage,prod}.yml
|-- db/migration/      схема БД (Flyway)
|-- static/            index.html, function.html, js/, css/, images/
`-- pages/admin.html   страница админа (отдаётся только админам)
src/test/              тесты Java и JS (JS запускаются из JsTestsTest через Node)
e2e/                   необязательные браузерные проверки (Playwright), не входят в сборку
docs/                  SSO, спецификация интеграции с AD, аудит
```

## Запуск локально

Нужны Java 21+, PostgreSQL и Node.js (для JS-тестов).

```powershell
$env:SPRING_PROFILES_ACTIVE = "local"
.\mvnw.cmd spring-boot:run
```

Приложение откроется на `http://localhost:8080`. Профиль обязателен: без `SPRING_PROFILES_ACTIVE` (`prod`, `stage` или
`local`) приложение не стартует. В `local` сайт открыт без входа, Swagger UI доступен на `/swagger-ui.html`.

Сотрудники для разработки без AD — синтетические из `mock/ad-employees.json`:

```powershell
$env:EMPLOYEE_SOURCE = "db"; $env:AD_SYNC_ENABLED = "true"; $env:AD_SYNC_MODE = "mock"; $env:AD_SYNC_ON_STARTUP = "true"
```

Тесты: `.\mvnw.cmd test`. Часть тестов работает с PostgreSQL из `DB_HOST` / `DB_NAME` и меняет данные, поэтому
запускайте их на отдельной базе (`$env:DB_NAME = "orgchart_test"`), а не на рабочей dev-базе.

## Вход

- `AUTH_SSO_ENABLED=true` (по умолчанию в `prod`): вход через Microsoft Entra ID (SAML 2.0).
- `AUTH_SSO_ENABLED=false` (по умолчанию в `stage` и `local`): сайт открыт всем без входа. Необязательно: `AUTH_PASSWORD_ENABLED=true` — форма с учётками из `AUTH_PASSWORD_USERS`.

Переменные, настройка Entra, включение, диагностика и откат: [docs/SSO.md](docs/SSO.md).

## How to grant admin access

There are two kinds of administrators:

- **Super admins**: the e-mails / account names in `SYSTEM_ADMIN_EMAILS` on the server. They can do everything
  admins can, manage the list of admins (Admin page > Administrators) and run the AD sync (`/api/admin/ad-sync`).
  They are not stored in the database and cannot be removed from the web UI.
- **Admins**: the enabled rows of the database table `app_admins`. They can edit the site content
  (`/api/manage/**`: overrides, function heads and later features). A change takes effect within 30 seconds
  (at once when made on the Admin page).

The value to store is the `you` field of `GET /api/me/roles` of that person (with SSO usually the e-mail/UPN,
with the password form the account name). Ask the person to open `/api/me/roles` while signed in, or let a super
admin add them on the Admin page (*Login name* lists all people: pick one and their e-mail is used, or type an
account name). In SQL:

```sql
INSERT INTO app_admins(login_name, note) VALUES ('name@kpmg.kz', 'why');
```

The comparison ignores case. To revoke access, delete the row or set `enabled = FALSE`. People who are not admins
see no admin controls, do not load the admin script, and every `/api/manage/**` call answers 403.

## Admin guide

Admin features need `EMPLOYEE_SOURCE=db` (with `excel` they answer 409 "requires EMPLOYEE_SOURCE=db" and the pages
hide their controls). The FAQ is the exception: it is edited in both modes. Every change is saved at once, shown to
all open pages within seconds (server-sent events) and written to the log with the admin, ids and field names only.

- **Access**: see "How to grant admin access" above. The Admin page (`/admin.html`, header link "Admin") has the tabs
  *Hidden people* (all admins) and *Administrators* (super admins).
- **Edit a person**: open the card, *Edit*. Group, area of duties and job description of the person in the function
  shown in the title (a person in two functions is edited per function). The hint under each field shows what Active
  Directory gives. If someone else saved meanwhile, *Reload values* shows the saved values and keeps your text.
- **Reset / remove**: in the edit form, *Reset to AD data* (the person's own function: manual changes go, the person
  stays with the AD data) or *Remove from function* (an extra function: the person leaves it).
- **Function heads**: the collapsible *Function heads* panel of a function page. One *main head* (root of the tree)
  and *additional heads*; *Make main*, reorder, remove, add (members of the function first, *All employees* for
  anybody). A hidden person cannot be a head.
- **Groups**: *Manage groups* on a function page. Groups from Active Directory (the department) are registered
  automatically and keep their name; admins create groups, rename them, move them left/right and delete empty ones.
  *Add people* / *Move to group…* move a person (one group per function, Undo in the message); *Remove from group*
  returns a person to the group from Active Directory (refused when that is the group they are in).
- **Hide / restore**: *Hide from site* on a card. The person disappears from the tree, groups and search for everyone;
  the people reporting to them are shown under the next manager. Restore them on the Admin page, *Hidden people*.
  The AD sync never changes this.
- **FAQ & Request Guidelines**: *Edit FAQ* in the header of the FAQ panel of a function page opens the editor in a
  dialog (its content scrolls, *Preview* / *Cancel* / *Save* stay at the bottom). Sections of guidelines, mailboxes or
  questions; add, remove and reorder sections and items; *Preview* before *Save*.
- **Validation rules** (the same in the browser and on the server, `FieldRules` / `FaqRules` and
  `static/js/validation.js`): texts are normalized (Unicode NFC, tabs and no-break spaces to spaces, repeated spaces
  collapsed, trimmed); refused are HTML characters `< >` (and backtick in labels), control characters, zero-width and
  text-direction characters, `javascript:` / `data:` / `vbscript:` and, in the FAQ, every link other than `https://` or
  `mailto:`. Labels (group, area of duties, job description, group names) allow letters, digits, spaces and
  `& / , . : ; - – — ( ) ' ’ " + # % _ ! ?` and must not start with `= + @`. Limits: group name 2-60, area of
  duties 120, job description 200, hide reason 200; FAQ section title 80, guideline 500, mailbox name 200, question
  200, answer 2000, 12 sections, 30 items per section. HEAD and Other are reserved group names.

| Method | Path | Who |
|---|---|---|
| GET | `/api/me/roles` | everyone (signed in; open site: anonymous answer) |
| GET | `/api/functions/{cs}/faq` | everyone who may see the site |
| GET | `/api/people/{id}/external` | everyone who may see the site (managers outside Central Services only) |
| GET | `/api/photos/{employeeId}` | everyone who may see the site (uploaded photo, or stored AD photo with `AD_PHOTOS_ENABLED=true`; db mode) |
| PUT / DELETE | `/api/me/photo` (body `image/jpeg` or `image/png`) | everyone signed in, their own photo only (found by their e-mail / UPN) |
| PUT / DELETE | `/api/manage/employees/{id}/photo` | admins |
| GET | `/api/manage/overrides?cs=&q=` | admins |
| PUT / DELETE | `/api/manage/overrides/{employeeId}?cs=` | admins |
| GET / PUT | `/api/manage/functions/{cs}/heads` | admins |
| GET / POST | `/api/manage/functions/{cs}/groups` | admins |
| PUT / DELETE | `/api/manage/groups/{id}` | admins |
| POST | `/api/manage/groups/{id}/members` | admins |
| DELETE | `/api/manage/groups/{id}/members/{employeeId}` | admins |
| POST / DELETE | `/api/manage/employees/{id}/hide` | admins |
| GET | `/api/manage/hidden` | admins |
| PUT | `/api/manage/functions/{cs}/faq` | admins |
| GET | `/admin.html` | admins |
| GET / POST | `/api/manage/admins` | super admins |
| DELETE | `/api/manage/admins/{id}` | super admins |
| POST | `/api/admin/ad-sync[?dryRun=true]`, GET `/api/admin/ad-sync/log` | super admins (`SYSTEM_ADMIN_EMAILS`) |
| POST | `/api/admin/ad-sync/photos[?force=true]` (the photo phase now) | super admins (`SYSTEM_ADMIN_EMAILS`) |

**Employee photos from AD** (`AD_PHOTOS_ENABLED`, default off; db mode only): after every successful sync the
`thumbnailPhoto` of new or changed employees is read one at a time on a background thread, validated (JPEG/PNG only,
size limits checked before decoding) and re-encoded as a clean JPEG in `employee_photos`. The site shows the AD photo,
then the folder photo (`PHOTO_SOURCE_PRIORITY=ad,folder` or `folder,ad`), then the initials. Hidden and disabled
people never get a photo. See `.env.example` for the keys.

**Uploaded photos** (db mode; independent of `AD_PHOTOS_ENABLED`): the camera button on the avatar of a profile card
opens *Your photo* on the signed-in person's own card, and *Photo of …* on every card for admins. The browser turns the
photo upright and scales it to 640 px; the server validates and re-encodes it like an AD photo (320 px) and stores it
as `source = MANUAL` (`uploaded_by` = who). An uploaded photo is shown first, is published at once, and the AD sync
never reads or overwrites it. *Remove photo* (the person or an admin) brings the AD photo back at once. Managers
outside Central Services get no uploaded photo. Undo every upload: `DELETE FROM employee_photos WHERE source =
'MANUAL'` (the next sync reads the AD photos again).

## UI conventions

- **Design tokens** live in `static/css/tokens.css` only: colors (KPMG-inspired palette; text/background pairs are
  checked for WCAG AA by `src/test/js/contrast.test.js`), the font stack `Univers, "Segoe UI", Arial, sans-serif`,
  font sizes, spacing (4 px scale), radii, shadows, focus ring, z-index and motion (`prefers-reduced-motion` is
  respected). One edit of a token retunes the site; `style.css` and `components.css` use only tokens.
- **Components** (`static/css/components.css`, `static/js/ui.js`): buttons (`btn-primary`, `btn-secondary`,
  `btn-ghost`, `btn-danger`), form fields (`field`, `field-label`, `field-help`, `field-error`, `field-counter`),
  chips and badges, cards, the segmented control, `createCollapsible`, `openDialog` / `confirmDialog` (native modal
  dialog, focus trap, ESC, focus return), `toast(message, type, {undo})`, skeleton / empty / error states, tables.
- **Rules**: UI language English; no `alert()`, `confirm()` or `prompt()`; no `innerHTML` in new code (DOM APIs and
  `textContent`; `StoredXssTest` pins the reviewed HTML sinks of each script); no external fonts, CDNs or scripts;
  visible keyboard focus everywhere; layouts from 360 px up.
- **Logo**: the header and the status cards show the white `/images/kpmg_white.png` straight on the brand blue; the
  favicon is the blue `/images/kpmg-logo.png` (`kpmg_blue.png` cropped to the logo); without the file a clearly marked
  placeholder.
- **Optional browser checks** (dev only, not part of the build): `e2e/` with its own `package.json` (Playwright with
  the installed Edge, axe-core): screenshots, axe, keyboard walk-through, performance. Start the app, then for example
  `cd e2e; npm install; $env:E2E_USER='dev'; $env:E2E_PASSWORD='<password>'; node axe.mjs`.

## Конфигурация

Все параметры задаются переменными окружения; полный список с пояснениями — в [.env.example](.env.example) и
[docs/SSO.md](docs/SSO.md). Основные:

| Переменная | Назначение |
|---|---|
| `SPRING_PROFILES_ACTIVE` | `prod`, `stage` или `local` (обязательно) |
| `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` | PostgreSQL (в `prod`/`stage` с SSL) |
| `EMPLOYEE_SOURCE` | `db` — сотрудники из AD (через БД), `excel` — из файла (откат) |
| `AD_SYNC_ENABLED`, `AD_SYNC_MODE`, `AD_LDAP_*` | синхронизация с AD ([docs/OrgChart_AD_Integration_Spec_v3.md](docs/OrgChart_AD_Integration_Spec_v3.md)) |
| `SYSTEM_ADMIN_EMAILS` | super admins (через запятую) |
| `APP_DATA_EXCEL_PATH`, `APP_DATA_PHOTOS_PATH` | Excel-файл и папка фото (в `local`: `EXCEL_PATH`, `PHOTOS_PATH`) |
| `SPRINGDOC_ENABLED` | Swagger UI и `/v3/api-docs`; по умолчанию включены только в `local` |

## Данные сотрудников и git

Excel-файл и фотографии — персональные данные, в git их нет (`data/` и `photos/*` в `.gitignore`, кроме
`photos/default.jpg`). Сервер и каждый разработчик держат свою копию в папках из `APP_DATA_EXCEL_PATH` /
`APP_DATA_PHOTOS_PATH`.

## Excel (режим `EMPLOYEE_SOURCE=excel`)

Приложение читает лист `NEW` (если его нет — первый лист) `.xlsx`, колонки по заголовкам первой строки: `Name`, `CS`,
`Group`, `Job Title`, `E-mail`, `PM`, `PM Email`, `PM Job Title`, `Area of Duties`, `Job Description` (регистр не важен). Строки без `name`, `cs` или `email` пропускаются. Файл
перечитывается при изменении (`FileWatcherService`), открытые страницы получают событие `org-updated`.

Фото из папки: имя файла — e-mail сотрудника или его часть до `@` (`.jpg`, `.jpeg`, `.png`, `.webp`); без фото — `photos/default.jpg`. В режиме
`db` первыми идут загруженные на сайте фото и фото из AD (см. выше).

## Сборка и деплой

- `build-tar.cmd` (или `build-tar.ps1`) собирает JAR, Docker-образ и `csorgchart_offline.tar` для сервера без
  интернета; на сервере `docker load -i csorgchart_offline.tar` и `docker compose up -d`.
- `docker-compose.yml`: приложение и PostgreSQL; пароль БД — `POSTGRES_PASSWORD` из `.env`, порт БД открыт только на
  `127.0.0.1`.
- CI (`.gitlab-ci.yml`): тесты на своём PostgreSQL, OWASP dependency-check, сборка образа.

## API

Все `/api/**` — только для тех, кто может открыть сайт (с SSO — после входа); без сессии ответ 401 JSON. Каждая запись
требует CSRF-токен: cookie `XSRF-TOKEN` возвращается в заголовке `X-XSRF-TOKEN`. Админские методы — в таблице раздела
*Admin guide* выше.

| Метод | Путь | Что делает |
|---|---|---|
| GET | `/api/functions` | список функций |
| GET | `/api/employees[?cs=&country=]` | сотрудники (все, функции, страны) |
| GET | `/api/search?q=[&cs=&country=]` | поиск |
| GET | `/api/org/stream` | SSE: `org-updated` при изменении данных |
| GET | `/api/likes/summary[/{email}]` | реакции |
| POST / DELETE | `/api/likes` | поставить / снять реакцию (`employeeEmail`, `reactionType` = `SOLVED` / `EXCEEDED`, `visitorId`) |
| POST | `/api/page-visits`, `/api/interactions`, `/api/search-logs` | аналитика |
| GET | `/actuator/health` | состояние (подробности — только super admin) |

Описание всех методов в формате OpenAPI: `/v3/api-docs` (включается `SPRINGDOC_ENABLED=true`, в `local` включено).

## Безопасность

- Content Security Policy без инлайн-скриптов для всех страниц сайта (`script-src 'self'`): не используйте `onclick=`
  и `<script>` без `src`, это проверяет `HardeningTest`. Исключение — страницы, которые пишет сам Spring (`/login`,
  отправка запроса в Entra).
- Нет CORS: API работает только с того же адреса.
- Аудит и рекомендации: [docs/AUDIT_2026-10-30.md](docs/AUDIT_2026-10-30.md).
