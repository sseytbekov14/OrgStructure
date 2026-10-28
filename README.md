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
