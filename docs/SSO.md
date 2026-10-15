# Вход через Microsoft Entra ID (SAML 2.0)

Настройка, включение, диагностика и откат для CS OrgChart. Ветка `feature/sso-saml`.

Сервер: TLS завершает само приложение (`server.ssl`, переменные `SERVER_SSL_*`), порт 8082, Windows `netsh portproxy 443 -> WSL:8082`, контейнер в `network_mode: host`, обратного прокси нет.

---

## 1. Как это работает

1. Страница без сессии → `302` на `/saml2/authenticate/entra` (Sign on URL). Запрошенная страница запоминается.
2. OrgChart отправляет в Entra AuthnRequest (HTTP-Redirect) и ждёт ответ в той же сессии.
3. Entra отвечает POST-ом на `/login/saml2/sso/entra` (Reply URL, ACS). Это единственный запрос, который принимается без CSRF-токена OrgChart.
4. Spring Security проверяет:
   - подпись (сертификат из метаданных Entra);
   - аудиторию (= `SAML_ENTITY_ID`);
   - получателя и Destination (= `SAML_ACS_URL`);
   - время и `InResponseTo`.
5. Допуск. Кого пускать, решает Entra: назначенная приложению группа. OrgChart пускает каждого, кого пропустил Entra:
   - роль `USER` получают все;
   - `SYSTEM_ADMIN_EMAILS` и `HR_EDITOR_EMAILS` добавляют `SYSTEM_ADMIN` и `HR_EDITOR`;
   - необязательный фильтр `SAML_ALLOWED_EMAIL_DOMAINS` отсекает чужие домены;
   - людей OrgChart не хранит и не создаёт, claim `groups` не читает.
6. Успешный вход: новая сессия и возврат на запрошенную страницу. В лог пишется `INFO SSO sign-in: roles=[USER] attributes=[…имена claims…]`.
7. Отказ: статическая страница `/access-not-granted.html` без деталей. В лог пишется `WARN SSO sign-in refused: reason=<ПРИЧИНА> (…) attributes=[…]`, только **имена** атрибутов, без значений и адресов.
8. Spring отклонил ответ (подпись, аудитория, время и т. п.): страница `/sign-in-failed.html`. В лог пишется `WARN SSO sign-in failed: <код> <описание Spring в одну строку, адреса замаскированы>`.
9. `/api/**` и SSE `/api/org/stream` без сессии отвечают `401 {"code":"SESSION_EXPIRED",…}`, а не редиректом. Страница на 401 перезагружается (не чаще раза в 10 секунд) и снова проходит вход. При живой сессии Microsoft это происходит без ввода пароля.
10. Сессия заканчивается после `SESSION_TIMEOUT` (по умолчанию 8 часов) без запросов. Единого выхода (SAML Single Logout) нет: кнопки выхода нет, `/logout` выключен.

---

## 2. Переменные окружения

| Переменная | prod | stage / local | Примечание |
|---|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `prod` | `stage` / `local` | **Обязательна.** Без неё приложение не стартует (`choose the profile explicitly`). Два профиля из prod/stage/local сразу — тоже отказ. `local` только для разработки без Entra. |
| `AUTH_SSO_ENABLED` | по умолчанию `true` | по умолчанию `false` | Ровно один из SSO и пароля должен быть включён, иначе старт отказывает. |
| `AUTH_PASSWORD_ENABLED` | пусто = противоположно SSO | | Форма `/login`, аварийный вход. Оба `true` или оба `false` — отказ старта. |
| `AUTH_PASSWORD_USERS` | пусто | **нужна** при пароле (кроме local) | `имя:bcrypt-хеш[,имя:bcrypt-хеш…]`. Только bcrypt (`$2a$`/`$2b$`/`$2y$`), открытый пароль — отказ старта. В `local` без неё создаётся разовая учётка `dev`, пароль печатается в лог при старте. |
| `AUTH_LOGOUT_ENABLED` | пусто → `false` при SSO | пусто → `true` при пароле | При SSO и `true` закрывается только сессия OrgChart, затем переход на `/signed-out.html`. |
| `SAML_ENTITY_ID` | `https://ccaorgchart.go1.kworld.kpmg.com` | — | Identifier (Entity ID) приложения в Entra, совпадение символ в символ. Шаблон `{baseUrl}…` не принимается. |
| `SAML_ACS_URL` | `https://ccaorgchart.go1.kworld.kpmg.com/login/saml2/sso/entra` | — | Reply URL в Entra. Абсолютный адрес, путь строго `/login/saml2/sso/entra`, иначе старт отказывает. |
| `SAML_METADATA_URI` | App Federation Metadata Url | — | Читается **один раз при старте**; если не читается, старт отказывает. Без выхода в интернет — `file:/путь/federationmetadata.xml`. |
| `SAML_ALLOWED_EMAIL_DOMAINS` | пусто (выкл.) | | Домены через запятую, регистр не важен. Домен берётся из claim e-mail, иначе из UPN. Не подходит или адреса нет — отказ (`DOMAIN_NOT_ALLOWED` / `NO_ADDRESS`). Поддомены не считаются. |
| `SYSTEM_ADMIN_EMAILS` | пусто = никого | | Адреса через запятую. Сравниваются с e-mail или UPN; при пароле — с именем учётки. |
| `HR_EDITOR_EMAILS` | пусто = никого | | Так же. |
| `AUTH_SSO_DEBUG_ENDPOINT` | `false` | `true` только на время проверки | `GET /me` показывает человеку claims его собственного входа. Включён в prod — WARN при старте. |
| `SESSION_TIMEOUT` | `8h` | | Формат Spring: `30m`, `8h`. |
| `SERVER_FORWARD_HEADERS_STRATEGY` | `none` | `none` | Не менять, пока перед приложением нет HTTP reverse proxy (см. ниже). |
| `SERVER_SERVLET_SESSION_COOKIE_SAME_SITE` / `_SECURE` / `_HTTP_ONLY` | пусто | пусто | При SSO по умолчанию `None` / `true` / `true`, без SSO — `Strict` / `false` / `true`. Задавать не нужно. |
| `SERVER_PORT`, `SERVER_SSL_*`, `SPRING_DATASOURCE_*`, `APP_DATA_*` | как сейчас | | Не меняются. |

**Важно про базу.** В профиле `prod` (и `stage`) адрес базы по умолчанию — `…?ssl=true&sslmode=require` из `application.yaml`. Сейчас сервер работает в `local`, где SSL к базе выключен. Если в окружении контейнера уже задан `SPRING_DATASOURCE_URL` (как в `docker-compose.yml` репозитория), он используется как есть. Если нет — задайте его явно, иначе после смены профиля приложение не подключится к PostgreSQL без SSL.

**`SERVER_FORWARD_HEADERS_STRATEGY`.** Перед приложением только TCP-проброс, TLS в самом приложении. Запрос уже несёт настоящую схему и хост, а заголовки `X-Forwarded-*` от клиента подделываемы, поэтому `none`. `native` включать только если поставят reverse proxy (IIS ARR, nginx, балансировщик), который сам завершает TLS и выставляет `X-Forwarded-Proto/Host/For`. `SAML_ACS_URL` в любом случае остаётся внешним адресом.

### Как получить bcrypt-хеш для `AUTH_PASSWORD_USERS`

В WSL на сервере или ноутбуке (`sudo apt install apache2-utils`):

```bash
htpasswd -nbBC 12 admin 'длинный-случайный-пароль'
# admin:$2y$12$....   <- это и есть значение "имя:хеш"
```

Символ `$` в хеше:
- в `.env` — значение в **одинарных** кавычках: `AUTH_PASSWORD_USERS='admin:$2y$12$...'`;
- прямо в `docker-compose.yml` — каждый `$` удвоить: `$$2y$$12$$...`.

---

## 3. Приложение в Entra (Enterprise application → Single sign-on → SAML)

1. **Basic SAML Configuration:**
   - Identifier (Entity ID) = `SAML_ENTITY_ID`;
   - Reply URL (ACS) = `SAML_ACS_URL`;
   - Sign on URL = `https://ccaorgchart.go1.kworld.kpmg.com/saml2/authenticate/entra`;
   - Relay State и Logout URL не нужны.

   Вместо ручного ввода можно загрузить метаданные SP: `https://ccaorgchart.go1.kworld.kpmg.com/saml2/metadata/entra` (открываются без входа, только при включённом SSO).
2. **Attributes & Claims.** Подходят значения по умолчанию. OrgChart читает:
   - NameID;
   - `http://schemas.xmlsoap.org/ws/2005/05/identity/claims/emailaddress` (`user.mail`);
   - `http://schemas.xmlsoap.org/ws/2005/05/identity/claims/name` (`user.userprincipalname`, это UPN; понимается и `…/claims/upn`).

   Claim `groups` не нужен.
3. **SAML Signing Certificate:**
   - SHA-256, подписывать assertion или ответ;
   - App Federation Metadata Url → `SAML_METADATA_URI`;
   - при ротации сертификата перезапустить OrgChart: метаданные читаются только при старте. Если используется `file:`, сначала обновить файл.
4. **Properties → Assignment required = Yes.** Без этого войти сможет **любой** пользователь тенанта, и AADSTS50105 не появится.
5. **Users and groups:** назначить группу сотрудников. Неназначенный человек получит AADSTS50105 ещё до OrgChart.

---

## 4. Пример настроек сервера

Только добавление переменных. Существующие `image`, `network_mode: host`, `volumes`, `SERVER_PORT=8082`, `SERVER_SSL_*`, `SPRING_DATASOURCE_*`, `APP_DATA_*` оставить как есть.

### docker-compose.yml (сервис приложения)

```yaml
services:
  csorgchart:
    # image, network_mode: host, volumes, restart — как сейчас
    environment:
      # --- существующие переменные (SERVER_PORT=8082, SERVER_SSL_*, SPRING_DATASOURCE_*, APP_DATA_*) не менять ---
      - SPRING_PROFILES_ACTIVE=prod
      - AUTH_SSO_ENABLED=${AUTH_SSO_ENABLED:-true}
      - AUTH_PASSWORD_ENABLED=${AUTH_PASSWORD_ENABLED:-}
      - AUTH_PASSWORD_USERS=${AUTH_PASSWORD_USERS:-}
      - AUTH_LOGOUT_ENABLED=${AUTH_LOGOUT_ENABLED:-}
      - SAML_ENTITY_ID=${SAML_ENTITY_ID}
      - SAML_ACS_URL=${SAML_ACS_URL}
      - SAML_METADATA_URI=${SAML_METADATA_URI}
      - SAML_ALLOWED_EMAIL_DOMAINS=${SAML_ALLOWED_EMAIL_DOMAINS:-}
      - SYSTEM_ADMIN_EMAILS=${SYSTEM_ADMIN_EMAILS:-}
      - HR_EDITOR_EMAILS=${HR_EDITOR_EMAILS:-}
      - AUTH_SSO_DEBUG_ENDPOINT=${AUTH_SSO_DEBUG_ENDPOINT:-false}
      - SESSION_TIMEOUT=${SESSION_TIMEOUT:-8h}
    # Только если метаданные берутся из файла (SAML_METADATA_URI=file:/saml/federationmetadata.xml):
    # volumes:
    #   - ./saml:/saml:ro
```

### .env (рядом с docker-compose.yml)

```dotenv
AUTH_SSO_ENABLED=true
SAML_ENTITY_ID=https://ccaorgchart.go1.kworld.kpmg.com
SAML_ACS_URL=https://ccaorgchart.go1.kworld.kpmg.com/login/saml2/sso/entra
SAML_METADATA_URI=https://login.microsoftonline.com/<tenant-id>/federationmetadata/2007-06/federationmetadata.xml?appid=<application-id>
# SAML_METADATA_URI=file:/saml/federationmetadata.xml
SAML_ALLOWED_EMAIL_DOMAINS=
SYSTEM_ADMIN_EMAILS=
HR_EDITOR_EMAILS=
AUTH_SSO_DEBUG_ENDPOINT=false
SESSION_TIMEOUT=8h
# Аварийный вход (нужен только при AUTH_SSO_ENABLED=false); одинарные кавычки обязательны из-за $
AUTH_PASSWORD_USERS=
```

---

## 5. Сборка образа и выкладка

### 5.1 Сборка на корпоративном ноутбуке

```powershell
git checkout feature/sso-saml
.\mvnw.cmd clean package
```

`package` запускает тесты:
- большинство работает на H2 в памяти и не требует ни базы, ни Entra;
- `CsOrgchartApplicationTests` и `ExcelServicePhotoTest` (профиль `local`) ждут PostgreSQL на `localhost:5432`. Без него запуск идёт так: `.\mvnw.cmd clean package "-Dtest=!CsOrgchartApplicationTests,!ExcelServicePhotoTest" "-Dsurefire.failIfNoSpecifiedTests=false"`.

OpenSAML публикуется **только** в репозитории Shibboleth (`https://build.shibboleth.net/maven/releases/`, прописан в `pom.xml`). Оттуда приходят:
- `org.opensaml`, версия 4.3.2: `opensaml-core`, `opensaml-saml-api`, `opensaml-saml-impl`, `opensaml-messaging-api`, `opensaml-profile-api`, `opensaml-security-api`, `opensaml-security-impl`, `opensaml-soap-api`, `opensaml-soap-impl`, `opensaml-storage-api`, `opensaml-xmlsec-api`, `opensaml-xmlsec-impl`, а также POM `opensaml-parent` 4.3.2;
- `net.shibboleth.utilities:java-support:8.4.2` и POM `net.shibboleth:parent:11.3.7`.

Всё остальное — из Maven Central. Если корпоративный Maven-прокси их не пропускает, сборка упадёт с `Could not resolve dependencies … org.opensaml:…:4.3.2`. Тогда:
- попросить добавить Shibboleth в прокси;
- или собрать там, где `~/.m2` уже содержит эти артефакты (копия каталогов `~/.m2/repository/org/opensaml` и `~/.m2/repository/net/shibboleth`).

`settings.xml` не трогать.

Проверить, что OpenSAML попал в jar:

```powershell
tar -tf target\cs-orgchart-0.0.1-SNAPSHOT.jar | findstr opensaml
# BOOT-INF/lib/opensaml-core-4.3.2.jar ... (12 jar)
```

### 5.2 Образ

`Dockerfile` репозитория собирает jar внутри Docker (`mvn` в стадии builder), то есть внутри Docker тоже нужен доступ к Shibboleth. Если его нет, соберите jar по 5.1 и образ из готового jar. Это временный файл, в репозиторий его класть не нужно:

```dockerfile
# Dockerfile.runtime
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S -g 10001 appgroup && adduser -S -u 10001 -G appgroup -s /sbin/nologin appuser
WORKDIR /app
COPY --chown=appuser:appgroup target/cs-orgchart-0.0.1-SNAPSHOT.jar /app/app.jar
USER appuser:appgroup
ENTRYPOINT ["java","-jar","/app/app.jar"]
```

```powershell
docker build -f Dockerfile.runtime -t csorgchart:sso .
docker save -o csorgchart-sso.tar csorgchart:sso
```

### 5.3 Выкладка на сервер (WSL)

```bash
docker tag csorgchart:latest csorgchart:before-sso   # для отката на старый образ
docker load -i csorgchart-sso.tar
docker tag csorgchart:sso csorgchart:latest           # или имя образа из вашего compose
# правки environment и .env по разделу 4
docker compose up -d
docker logs -f csorgchart                              # строка "SSO with Microsoft Entra ID: ..." (раздел 6)
```

Если выкладка идёт через PowerShell-скрипты, шаги те же: новый образ плюс переменные из раздела 4.

---

## 6. Порядок включения

1. Создать или проверить приложение Entra (раздел 3) с адресами `ccaorgchart.go1.kworld.kpmg.com`. Назначить группу, Assignment required = Yes.
2. На сервере проверить доступ к метаданным: `curl -sI "<App Federation Metadata Url>"` из WSL должен вернуть `200`. Нет доступа или ошибка TLS → скачать XML в браузере на ноутбуке, положить в `./saml/federationmetadata.xml`, `SAML_METADATA_URI=file:/saml/federationmetadata.xml`.
3. Проверить часы WSL: `date -u` против точного времени. Расхождение больше минуты → `sudo hwclock -s` или `wsl --shutdown` на Windows.
4. Переменные по разделу 4, на время проверки `AUTH_SSO_DEBUG_ENDPOINT=true`. Перезапуск.
5. В логе при старте должна быть строка:
   `SSO with Microsoft Entra ID: Identifier (Entity ID) https://ccaorgchart…, Reply URL (ACS) https://ccaorgchart…/login/saml2/sso/entra, Sign on URL https://ccaorgchart…/saml2/authenticate/entra, identity provider https://sts.windows.net/<tenant-id>/`
6. Открыть `https://ccaorgchart.go1.kworld.kpmg.com/saml2/metadata/entra`: `entityID` и `Location` должны совпадать с Entra.
7. Проверка с 2–3 людьми:

| # | Шаг | Ожидается |
|---|---|---|
| 1 | Открыть `https://ccaorgchart…/function.html?cs=<функция>` в чистом окне браузера | Переход в Microsoft, после входа возврат на ту же страницу |
| 2 | `/me` | Свои NameID, claims (emailaddress, name) и `roles` |
| 3 | Человек из `SYSTEM_ADMIN_EMAILS` | В `/me` `ROLE_SYSTEM_ADMIN` |
| 4 | Лайк, профиль, поиск | Работают (CSRF-заголовок уходит; в DevTools у POST нет 403) |
| 5 | Человек, не назначенный в Entra | Ошибка Microsoft AADSTS50105, до OrgChart не доходит |
| 6 | DevTools → Application → Cookies | `JSESSIONID`: `Secure`, `HttpOnly`, `SameSite=None`; `XSRF-TOKEN` без `HttpOnly` |
| 7 | Удалить cookie `JSESSIONID` и нажать лайк | Страница перезагружается и снова входит через Microsoft |

8. Выключить `AUTH_SSO_DEBUG_ENDPOINT` и перезапустить.

---

## 7. Диагностика

| Симптом | Где смотреть | Причина | Что делать |
|---|---|---|---|
| Не стартует: `OrgChart does not start: choose the profile explicitly` | лог старта | Не задан `SPRING_PROFILES_ACTIVE` | `SPRING_PROFILES_ACTIVE=prod` в environment контейнера |
| Не стартует: `AUTH_SSO_ENABLED and AUTH_PASSWORD_ENABLED are both true` / `both false` | лог старта | Противоречивые переключатели | Ровно один `true` (или `AUTH_PASSWORD_ENABLED` пусто) |
| Не стартует: `the password form is on but AUTH_PASSWORD_USERS is empty` / `is not a bcrypt hash` | лог старта | Пароль включён без учёток или с открытым паролем | Хеш по разделу 2 |
| Не стартует: `SSO is on … but SAML_ENTITY_ID is not set` / `SAML_ACS_URL must end with /login/saml2/sso/entra` | лог старта | Пусто или неверно | Исправить переменную |
| Не стартует: `the Entra metadata of SAML_METADATA_URI cannot be read` (часто с `PKIX path building failed`, `UnknownHost`, `Connection timed out`) | лог старта | Нет выхода к `login.microsoftonline.com`, корпоративная TLS-инспекция, прокси, неверный URL | Проверить `curl` из WSL; иначе `file:` с XML (раздел 6, шаг 2) |
| **Нет классов OpenSAML**: при сборке `Could not resolve … org.opensaml`, при старте `NoClassDefFoundError: org/opensaml/…` или `ClassNotFoundException: net.shibboleth…` | лог сборки / старта | Maven-прокси не пропустил Shibboleth; в образ попал jar без OpenSAML | Раздел 5.1: доступ к Shibboleth или сборка с готовым `~/.m2`; проверить `tar -tf … | findstr opensaml` |
| Microsoft: **AADSTS50011** (reply URL does not match) | страница Microsoft | Reply URL в Entra ≠ `SAML_ACS_URL` | Совпадение символ в символ: `https`, хост без порта, путь `/login/saml2/sso/entra` |
| Microsoft: **AADSTS700016** (application not found in the directory) | страница Microsoft | **Entity ID**: Identifier в Entra ≠ `SAML_ENTITY_ID` | Выровнять: `https://ccaorgchart.go1.kworld.kpmg.com` и там, и там |
| Microsoft: **AADSTS50105** (user not assigned to a role) | страница Microsoft | Человек не входит в назначенную группу | Users and groups в Entra; в OrgChart ничего делать не нужно |
| «Sign-in did not complete», `SSO sign-in failed: invalid_assertion … audience` | лог OrgChart | **Entity ID**: `SAML_ENTITY_ID` ≠ Identifier, которым Entra подписал аудиторию | Выровнять |
| То же, `invalid_destination` или `invalid_assertion … recipient` | лог | Entra отправил ответ на другой адрес, чем `SAML_ACS_URL` | Выровнять Reply URL и `SAML_ACS_URL` |
| То же, `invalid_signature` | лог | Сертификат Entra сменился, а OrgChart прочитал метаданные до этого (или устаревший `file:`) | Перезапустить; обновить файл |
| То же, `invalid_in_response_to` / `no saved authentication request was found` | лог | Ответ пришёл без cookie сессии: SameSite не `None`, нет `Secure`, сайт открыт по http; вход начат в другой вкладке; прошло больше `SESSION_TIMEOUT` | Проверить cookie (раздел 6, шаг 7.6) и `https`; повторить вход |
| То же, `invalid_assertion … NotOnOrAfter / NotBefore` (**часы**) | лог | Часы WSL отстали или убежали (типично после сна ноутбука / хоста) | `date -u` в WSL; `sudo hwclock -s` или `wsl --shutdown`; NTP на Windows |
| Бесконечные переходы OrgChart ↔ Microsoft | DevTools → Network | Браузер не сохраняет `JSESSIONID` (Secure на http, политика браузера) | Только `https`; не задавать `SERVER_SERVLET_SESSION_COOKIE_SECURE=false` при SSO |
| «Access is not granted», `reason=DOMAIN_NOT_ALLOWED` | лог (`attributes=[…]` показывает, какие claims пришли) | Адрес не на `SAML_ALLOWED_EMAIL_DOMAINS` | Добавить домен или очистить переменную |
| «Access is not granted», `reason=NO_ADDRESS` | лог; в `attributes=[]` нет `emailaddress`/`name` | Фильтр доменов включён, а Entra не прислал ни e-mail, ни UPN | Добавить claims в Entra (раздел 3, п. 2) |
| Лайки / поиск не сохраняются, в DevTools POST → 403 | DevTools | Нет cookie `XSRF-TOKEN` (заблокирована) или кэш старого JS | Обновить страницу с очисткой кэша (`Ctrl+F5`), скрипты версии `?v=2.7` |
| `/me` отдаёт 404 | | `AUTH_SSO_DEBUG_ENDPOINT` выключен | Норма для prod |

Строки отказа и сбоя не содержат адресов и значений claims: только причина и имена атрибутов.

---

## 8. Откат

### 8.1 На вход по паролю (тот же образ)

1. Задать `AUTH_SSO_ENABLED=false` и `AUTH_PASSWORD_USERS='admin:$2y$12$...'` (раздел 2). `SAML_*` можно оставить: без SSO они не читаются.
2. Перезапустить. Появятся форма `/login` и `/logout`. Cookie сессии вернётся к `SameSite=Strict`, без `Secure`, если `SERVER_SERVLET_SESSION_COOKIE_*` не заданы явно.
3. Войти смогут только учётки из `AUTH_PASSWORD_USERS`. Роли берутся из `SYSTEM_ADMIN_EMAILS` / `HR_EDITOR_EMAILS` по имени учётки.

### 8.2 На старый образ (без всякого входа)

```bash
docker tag csorgchart:before-sso csorgchart:latest
docker compose up -d
```

Старый образ запускается в `local` без входа. Новые переменные ему не мешают.

---

## 9. Локальная разработка

```powershell
$env:SPRING_PROFILES_ACTIVE = "local"
.\mvnw.cmd spring-boot:run
# в логе: local profile without AUTH_PASSWORD_USERS: sign in as 'dev' with the one-off password ...
```

Entra не нужен. Тесты SSO работают с поддельным IdP: ключ и сертификат создаются при запуске тестов, в репозитории ключей нет.

---

## 10. Что нельзя проверить без настоящего Entra

- **Метаданные Entra:** доступность `login.microsoftonline.com` из WSL, TLS-инспекция, ротация сертификата.
- **Claims:** какие реально приходят и под какими именами, формат NameID, что подписывается — ответ или assertion.
- **Microsoft:** коды AADSTS, Conditional Access и MFA, назначение групп.
- **Браузер:** `SameSite=None; Secure` при настоящем cross-site POST через `netsh portproxy` с TLS в приложении, политики браузеров компании.
- **Часы:** расхождение часов WSL с Microsoft.
- **Портал My Apps:** вход из него (проверить, что идёт через Sign on URL).
