# System Architecture: CS OrgChart

## 1. System Overview
The **CS OrgChart** application is an interactive organizational structure (expertise catalog) for Central Services.
The goal of the system is to provide employees with convenient access to Central Services functions, display role distribution and employee contacts (with Outlook integration), and enable quick search by functions, tasks, and expertise.

The system is built on a classic client-server architecture:
- **Backend**: A REST API server based on Java (Spring Boot) responsible for business logic, search, and data updates.
- **Frontend**: A lightweight web application (HTML, CSS, Vanilla JS) providing an interactive user interface.
- **Database**: A relational database management system (PostgreSQL) for storing structured employee data.
- **File System**: Storage for raw data (Excel file) and media files (photos).

---

## 2. Tech Stack

### Backend
- **Programming Language:** Java 21
- **Framework:** Spring Boot 3.5.14
- **Web Layer:** Spring Web (REST API, Server-Sent Events)
- **Data Access:** Spring Data JPA, Hibernate
- **File Processing:** Apache POI (reading `.xlsx` files), Apache Commons IO (FileAlterationMonitor for tracking changes)
- **Utilities:** Lombok (reducing boilerplate code)
- **API Documentation:** SpringDoc OpenAPI (Swagger)
- **Monitoring:** Spring Boot Actuator

### Frontend
- HTML5, CSS3, Vanilla JavaScript
- Server-Sent Events (SSE) for reactive UI updates

### Database
- **DBMS:** PostgreSQL 15 (deployed via Docker)
- **Database Schema:** Automatic generation and updates via Hibernate (`ddl-auto: update`)

### Infrastructure and Deployment
- Docker, Docker Compose (containerization of the application and database)
- Maven Wrapper (`mvnw`) for local builds

---

## 3. Application Architecture

The Backend source code is divided into layers (Domain-Driven / Layered Architecture), which is reflected in the package structure under `src/main/java/cs_orgchart/`:

1. **`model`** (or `entity`): JPA entities (e.g., `Employee`) defining the database table structures.
2. **`repository`**: Spring Data interfaces for interacting with the PostgreSQL database (search, save, filter).
3. **`service`**: Business logic layer.
   - Reading and parsing the Excel file (using Apache POI).
   - Employee search logic (by function, name, job title).
   - A file monitoring service (`FileWatcherService`) that reacts to changes in the `.xlsx` file.
4. **`controller`**: REST controllers for handling HTTP requests from the client.
   - API for employees and functions lists (`/api/employees`, `/api/functions`, `/api/search`).
   - SSE controller (`/api/org/stream`) for broadcasting events to clients.
5. **`config`**: Spring configuration classes (CORS, Swagger, etc.).
6. **`util`**: Helper utility classes.

### Data Flow
1. Data is edited and saved in `.xlsx` format (in the `data/` folder).
2. The built-in `FileWatcherService` detects the file modification.
3. The service parses the Excel file and updates records in the PostgreSQL database.
4. The service sends an `org-updated` event via the SSE subscription (`/api/org/stream`).
5. The Frontend intercepts the event and automatically updates the tree structure without requiring a page reload by the user.

---

## 4. Data Storage

### Database (PostgreSQL)
The primary storage for processed data to enable fast search and filtering. Connected via JDBC (`jdbc:postgresql://db:5432/OrgStructure`).

### File System (Source of Truth)
- **Excel (`result.xlsx`):** Acts as the "master system" for data. Contains columns: `name`, `cs` (function), `group`, `jobTitle`, `email`, as well as manager details (`pm`, `pmEmail`, `pmJobTitle`).
- **`photos/` folder:** Photo storage. Photos are matched to employees by email (`employee@company.com.jpg`). If a photo is missing, `default.jpg` is used.

---

## 5. Integrations

- **Real-time Notifications (SSE):** Implemented to synchronize all active clients after a new Excel document is uploaded or modified.
- **MS Outlook Integration:** The employee UI card generates a `mailto:` link, allowing users to open their email client with a pre-filled address to contact Central Services staff in one click.

---

## 6. Deployment

The application is designed to be deployed using **Docker Compose**.
The `docker-compose.yml` file defines 2 services:

1. **`db`** (`postgres:15-alpine`):
   - Stores data in a mounted volume `./pgdata`.
2. **`csorgchart`** (Spring Boot application):
   - Port mapping `8082:8080`.
   - Volume mounts: `./data` (read-only, for `.xlsx`) and `./photos` (read-only).
   - Depends on the database (`depends_on: db`).

Environment configuration is managed via Environment Variables, which override settings in `application.yaml`:
- `APP_DATA_EXCEL_PATH` — path to the Excel file inside the container.
- `APP_DATA_PHOTOS_PATH` — path to the photos folder.
- Database connection settings (`SPRING_DATASOURCE_URL`, `USERNAME`, `PASSWORD`).
