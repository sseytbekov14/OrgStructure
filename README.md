# CS OrgChart

Интерактивная оргструктура Central Services для быстрого поиска сотрудников, функций и контактов по данным из Excel.

## Что умеет приложение

- Показывает список функций Central Services на главной странице.
- Открывает отдельную страницу функции со структурой сотрудников.
- Поддерживает поиск по имени, должности, группе и функции.
- Подтягивает фотографии сотрудников из локальной папки.
- Автоматически перечитывает Excel при изменении файла.
- Отправляет событие обновления в браузер через Server-Sent Events (SSE).

## Стек

- Java 21
- Spring Boot 3.5.14
- Spring Web
- Spring Boot Actuator
- Apache POI
- Apache Commons IO
- HTML, CSS, Vanilla JavaScript

## Структура проекта

```text
CS_OrgChart/
|-- data/
|   `-- result.xlsx
|-- photos/
|   `-- default.jpg
|-- src/
|   |-- main/
|   |   |-- java/cs_orgchart/
|   |   |   |-- config/
|   |   |   |-- controller/
|   |   |   |-- model/
|   |   |   `-- service/
|   |   `-- resources/
|   |       |-- application.yaml
|   |       `-- static/
|   |           |-- index.html
|   |           |-- function.html
|   |           |-- css/
|   |           `-- js/
|   `-- test/
|-- pom.xml
`-- mvnw.cmd
```

## Запуск локально

### Требования

- Java 21

### Команда запуска

```powershell
$env:SPRING_PROFILES_ACTIVE = "local"
.\mvnw.cmd spring-boot:run
```

После запуска приложение будет доступно по адресу `http://localhost:8080`. Профиль обязателен: без `SPRING_PROFILES_ACTIVE` (`prod`, `stage` или `local`) приложение не стартует. В `local` сайт открыт без входа.

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
admin add them on the Admin page. In SQL:

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
- **FAQ & Request Guidelines**: *Edit FAQ* in the panel of a function page. Sections of guidelines, mailboxes or
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
- **Logo**: the header shows `/images/kpmg-logo.svg` (or `.png`) once the official file is placed there; until then
  a clearly marked placeholder.
- **Optional browser checks** (dev only, not part of the build): `e2e/` with its own `package.json` (Playwright with
  the installed Edge, axe-core): screenshots, axe, keyboard walk-through, performance. Start the app, then for example
  `cd e2e; npm install; $env:E2E_USER='dev'; $env:E2E_PASSWORD='<password>'; node axe.mjs`.

## Конфигурация

Основные пути задаются в `src/main/resources/application.yaml`:

```yaml
server:
  port: 8080

app:
  data:
    excel-path: C:/Sultan/Projects/CS_OrgChart/data/result.xlsx
    photos-path: C:/Sultan/Projects/CS_OrgChart/photos
```

Если Excel или фотографии лежат в другом месте, достаточно изменить эти пути.

## Формат Excel

Приложение читает первый лист `.xlsx` и ожидает такие колонки:

| Колонка | Описание |
|---|---|
| A | `name` - ФИО сотрудника |
| B | `cs` - функция Central Services |
| C | `group` - группа или команда |
| D | `jobTitle` - должность |
| E | `email` - корпоративная почта |
| F | `pm` - руководитель |
| G | `pmEmail` - почта руководителя |
| H | `pmJobTitle` - должность руководителя |

Строки без `name`, `cs` или `email` пропускаются.

## Фотографии

- Фотографии хранятся в папке `photos/`.
- Имя файла должно совпадать с email сотрудника.
- Формат файла: `.jpg`
- Если фото не найдено, используется `photos/default.jpg`.

Пример:

```text
photos/
|-- employee@company.com.jpg
`-- default.jpg
```

## API

### Сотрудники и функции

- `GET /api/functions` - список функций.
- `GET /api/employees` - все сотрудники.
- `GET /api/employees?cs=People` - сотрудники выбранной функции.
- `GET /api/search?q=finance` - поиск сотрудников.

### Поток обновлений

- `GET /api/org/stream` - SSE-подписка на изменения оргструктуры.

При изменении Excel сервер:

1. перечитывает данные;
2. увеличивает версию;
3. отправляет клиентам событие `org-updated`.

## Автообновление данных

`FileWatcherService` следит за файлом Excel через Apache Commons IO `FileAlterationMonitor`.

- По умолчанию проверка идёт каждые `1000` мс.
- После изменения файла данные перечитываются без перезапуска приложения.
- На фронтенд отправляется уведомление, чтобы клиент увидел актуальную структуру.

## Полезно знать

- Главная страница: `/`
- Страница функции: `/function.html`
- Фото сотрудников доступны по пути `/photos/...`
- Для `index.html`, `function.html` и `favicon.svg` отключено кеширование

## Текущее состояние

README отражает текущую реализацию в репозитории: локальный запуск через Maven Wrapper, Excel-файл `data/result.xlsx`, фото из папки `photos/` и обновление структуры через SSE. Инструкции по Docker не добавлены, потому что в текущем репозитории `Dockerfile` и `docker-compose.yml` отсутствуют.
