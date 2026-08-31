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
.\mvnw.cmd spring-boot:run
```

После запуска приложение будет доступно по адресу `http://localhost:8080`.

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
