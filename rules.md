# Pohr — система управления VPN-инфраструктурой

## 📌 Что это

**Pohr** (`roclh/pohr`) — self-hosted платформа для раздачи VPN-доступа через подписки. Состоит из двух частей: **RU-мост** (зеркало, оркестратор, входная точка, UI) и **EU-узел** (выходной узел с Xray). Задумана как отказоустойчивая замена ручной настройки VPN в условиях усиливающихся блокировок РКН.

**Цель проекта:** «нажал кнопку — всё работает», при этом максимально скрытно от провайдера VPS и РКН, с универсальным конфигурированием через web UI.

**Владелец:** Roclh (Java-разработчик, ник в GitHub/Docker Hub — `roclh`).

---

## 🎯 Целевая картинка продукта

1. **Простота развёртывания.** Один скрипт (`install.sh` / `wget | bash`) поднимает всё на RU-сервере. EU-узел разворачивается одной curl-командой.
2. **Скрытность от провайдера VPS.** Xray маскируется под безобидный процесс, трафик — под TLS через Reality/self-steal. Администратор провайдера не должен понять, что на VPS крутится прокси.
3. **Универсальность конфигурирования.** Админ через web UI редактирует конфиг Xray, управляет пользователями, выдаёт подписки, обновляет Xray без пересборки приложения.
4. **Подписки как универсальный механизм.** Пользователь получает subscription URL, который работает в v2rayN, Happ, Streisand, v2rayNG, Karing, Shadowrocket.
5. **Отказоустойчивость.** При блокировке EU-узла — админ поднимает новый, RU-мост автоматически перенастраивает маршрут. Клиентский конфиг не меняется.
6. **Низкое потребление ресурсов.** RU-мост (Spring Boot Native) — ~60–140 МБ RSS. Помещается на дешёвый VPS 512 МБ–1 ГБ.
7. **Минимум ручной работы.** Подписки создаются автоматически при первом заходе пользователя. Админ занимается только инфраструктурой.

---

## 🏗️ Архитектура

### Схема маршрутизации

```
Клиент → RU-мост (российский VPS, Spring Boot + UI) → EU-узел (зарубежный VPS, Xray) → интернет
```

### Роли компонентов

| Компонент | Роль |
|---|---|
| **RU-мост** | Веб-UI, REST API, SQLite, зеркало GitHub, генерация подписок, оркестрация EU-узлов |
| **EU-узел** | Xray, принимает трафик от RU-моста, выпускает в интернет |
| **Subscription URL** | Единая точка получения конфигов клиентом, обновляется автоматически |
| **SQLite** | Пользователи, подписки, Xray-конфиги, метаданные EU-узлов |

### Ключевая архитектурная идея: конфиг в БД

Xray-конфиг **не хранится в файле**. Источник истины — таблица `xray_configs`. Файл `xray/config/config.json` — это **материализация** активного конфига + инъекция клиентов из активных подписок. Происходит:
- при старте приложения (`XrayAutoInstaller`),
- перед каждым `start()`/`restart()` Xray (`XrayProcessManager.start()` → `XrayConfigMaterializer.materialize()`).

Это даёт:
- десятки экспериментов с конфигами без пересборки,
- A/B между «профилями подключения» (xhttp vs tcp, разные SNI/dest),
- откат к рабочему конфигу одним кликом.

### Клиентские скрипты: тоже вне образа

Скрипты установки на стороне клиента (сейчас — только `v2rayn-setup.{bat,ps1}`) живут по той же философии, что и Xray-конфиги:

- **Бандл в образе** (`classpath:scripts/*`) — эталон, обновляется вместе с релизом.
- **Volume `SCRIPTS_HOME`** — рабочая копия, правится админом через `/admin/client-scripts`.
- **Seed при первом старте** — если в volume пусто, копируем из бандла; если файл уже есть — не трогаем.

Это даёт:
- правки админа переживают обновление Docker-образа,
- кнопка **Reset to bundled** — мгновенный откат к эталону,
- `.history/` — до 10 последних версий каждого скрипта.

### Стратегия сокрытия от провайдера VPS

- **Уровень 1: маскировка процесса.** Xray переименован в `nginx-worker` или `systemd-resolved`. Запуск через `exec -a <fakename>` или отдельный бинарник с безобидным именем.
- **Уровень 2: маскировка трафика.** Reality (VLESS + Reality) — TLS-трафик к чужому домену, неотличимый от обычного HTTPS. `self-steal` — заглушка-сайт на том же IP.
- **Уровень 3: инверсия RU↔EU.** На RU-сервере вообще нет Xray — только SSH-демон. EU-узел инициирует туннель через `ssh -R`. Для провайдера RU-сервера — обычная SSH-сессия.
- **Уровень 4: борьба с мониторингом.** Провайдеры VPS часто ставят агенты, сканирующие процессы/порты. Крайняя мера — чистая ОС без агентов.

---

## ✅ Что уже сделано

### Инфраструктура

- [x] Monorepo Gradle, Java 25, Spring Boot 4.0.8
- [x] GraalVM Native Image настроен, бинарник ~98 МБ, старт 60 мс, RSS 62 МБ (без JPA), 140 МБ (с JPA + Liquibase)
- [x] Docker-образ собирается, профили `dev`/`prod`
- [x] `.gitignore`, `.dockerignore`
- [x] `docker-compose.dev.yml` (Xray отдельно) и `docker-compose.prod.yml` (all-in-one)

### База данных

- [x] SQLite через `org.xerial:sqlite-jdbc:3.49.1.0`
- [x] Liquibase, SQL-миграции в `db/changelog/001-init/`, `002-subscriptions/`, `003-xray-configs/`, `004-subscriptions-uuid/`
- [x] `ddl-auto: validate` (Liquibase управляет схемой, Hibernate только проверяет)
- [x] `InstantStringConverter` — `Instant` ↔ ISO-8601 строка с `Z` (SQLite не имеет TIMESTAMPTZ)
- [x] `JdbcTypeCode(SqlTypes.VARCHAR)` для UUID, `JdbcTypeCode(SqlTypes.INTEGER)` для boolean

### Модели и сервисы

- [x] `User` (UUID, username, passwordHash, role, enabled, createdAt)
- [x] `Subscription` (UUID, userId, token, **xrayUuid**, enabled, createdAt)
- [x] `XrayConfig` (UUID, name, description, content, realityPublicKey, active, createdAt, updatedAt)
- [x] `UserRepository`, `SubscriptionRepository`, `XrayConfigRepository`
- [x] `UserService`, `SubscriptionService`
- [x] `DatabaseUserDetailsService` — интеграция с Spring Security
- [x] `AdminInitializer` — создаёт `admin / changeme` при первом старте

### UI (Spring MVC + Thymeleaf)

- [x] Страницы: `/login`, `/home`, `/admin`
- [x] `/admin/users` — CRUD пользователей
- [x] `/admin/subscriptions` — список подписок, копирование URL, toggle, delete
- [x] `/admin/xray` — статус процесса, install/update, кнопки Start/Stop/Restart, live-логи через WebSocket
- [x] `/admin/xray/configs` — список конфигов, Edit / Activate / Delete / **Clone**
- [x] `/admin/xray/configs/new`, `/admin/xray/configs/{id}/edit` — textarea-редактор JSON с Tab-индентацией и Format JSON
- [x] Баннер «это активный конфиг» в форме редактирования
- [x] Валидация JSON + Reality public key
- [x] `LocaleConfig` — переключение RU/EN через cookie, i18n через `.properties`
- [x] Bootstrap-free CSS, светлая тема

### Xray

- [x] `XrayInstaller` — скачивание и установка с GitHub или fallback-зеркала
- [x] `XrayPlatform` — автоопределение ОС/арх
- [x] `XrayVersionResolver` — получение последней версии через GitHub API
- [x] `XrayProcessManager` — ProcessBuilder, PID, логи в буфер, синхронизация, **PID-файл + stale-recovery при старте**
- [x] `XrayRealityService` — генерация x25519-ключей и shortId, derive publicKey из privateKey (поддержка форматов `Private key:` и нового `PrivateKey:` / `Password (PublicKey):`)
- [x] `XrayConfigService` — CRUD конфигов, `buildVlessLinks(subscription)`, `parseLinks` с поддержкой **xhttp + Reality + fp/spx/alpn из JSON**
- [x] `XrayConfigMaterializer` — материализует активный конфиг из БД + инжектит клиентов из активных подписок
- [x] `XrayVersionRegistry` — хранит установленную версию в файле
- [x] `XrayAutoInstaller` — установка + `ensureDefault()` + `materialize()` при старте
- [x] `XrayAutoInstaller` — не переустанавливает Xray при каждом старте, если бинарник уже есть и `XRAY_VERSION` не задан явно (ручной апдейт через UI переживает рестарт контейнера)
- [x] `X-Pohr-Xray-Version` header в `/api/nodes/{id}/config` — EU-нода узнаёт версию Xray на RU
- [x] WebSocket `/ws/xray-logs` — live-стрим логов

### Безопасность

- [x] Spring Security, form login
- [x] `BCryptPasswordEncoder`
- [x] `/sub/**` — permitAll (токен = секрет)
- [x] `/ws/**` — authenticated
- [x] `/admin/**` — hasRole('ADMIN')
- [x] CSRF на всех POST-формах
- [x] Защита от удаления себя, отключения последнего админа

### Подписки

- [x] Эндпоинт `GET /sub/{token}` — отдаёт Base64-список `vless://` ссылок, **UUID per-user**
- [x] Заголовки `Profile-Title`, `Profile-Update-Interval: 6`, `Subscription-Userinfo`
- [x] URL подписки собирается динамически с учётом host/port запроса
- [x] Подписка создаётся автоматически при первом заходе пользователя на `/home`
- [x] `GET /sub/{token}/rules.json` — routing rules для v2rayN (ru-direct)
- [x] `XrayConfigService.parseLinks` строит одну ссылку на подписку с её `xrayUuid`, без перебора чужих UUID из `clients[]`
- [x] `fp` в vless-ссылке читается из `realitySettings.fingerprint` конфига, не хардкод
- [x] `flow=xtls-rprx-vision` добавляется только при `network=tcp`, для xhttp — не добавляется

### Клиентские скрипты (установщики)

- [x] `ClientScriptService` — CRUD над скриптами в volume `SCRIPTS_HOME`, seed из `classpath:scripts/*` при первом старте (файлы админа переживают обновление образа)
- [x] `renderBytes` с per-extension кодировкой: `.bat`/`.cmd` → **Cp866** (кириллица в cmd), `.ps1` → **UTF-8 + BOM** (PowerShell 5.1)
- [x] `/admin/client-scripts` — список скриптов с бейджами `bundled` / `modified` / `custom`, Edit / Reset to bundled / Delete
- [x] `/admin/client-scripts/new`, `/admin/client-scripts/{name}/edit` — редактор в textarea с Tab-индентацией
- [x] `/sub/{token}/setup.bat` и `/sub/{token}/setup.ps1` — отдают рендерленный скрипт с подстановкой `{{SUBSCRIPTION_URL}}`, `{{RULES_URL}}`, `{{PS1_URL}}`, `{{BASE_URL}}`, `{{TOKEN}}`
- [x] Кнопка «Настроить v2rayN» на `/home`, скачивает персональный `.bat`
- [x] v2rayn-setup.ps1 умеет: найти v2rayN (7 уровней поиска: saved-path → process → registry → Start Menu → PATH → common paths → рекурсивный поиск → диалог), найти `guiNConfig.json` (v6 в корне, v7 в `guiConfigs/`), сделать pristine + timestamped бэкапы, включить фрагмент в правильных полях, запустить v2rayN
- [x] Режим `-Restore` — восстановление `guiNConfig.json` из pristine-бэкапа
- [x] `docker-compose.prod.yml` — volume `pohr-scripts:/app/scripts`, `SCRIPTS_HOME=/app/scripts`
- [x] `NativeHints` — `hints.resources().registerPattern("scripts/**")`

### Native Image и GraalVM

- [x] `NativeHints` — регистрация `org.sqlite.JDBC`, `SQLiteDialect`, Liquibase-парсеров
- [x] `hints.resources().registerPattern("db/changelog/**")`, `i18n/messages*`
- [x] Работает установка Xray из нативного образа (Java HTTP Client, ZipInputStream)

### EU-ноды (orchestration)

- [x] `EuNode`, `EnrollmentToken`, `NodeStatus` — модель данных
- [x] `EuNodeRepository`, `EnrollmentTokenRepository`
- [x] `EuNodeService` — CRUD, токены, register/update, health, ротация секрета/туннеля
- [x] `EuConfigService` — генерация EU-конфига (`buildEuConfig`), outbound к EU (`buildEuOutbound`), healthcheck-inbound (`buildHealthcheckInbound`), routing (`buildRouting`)
- [x] `EuNodeApiController` — `/api/nodes/bootstrap.sh`, `/register`, `/{id}/config`, `/{id}/health`, `agent.sh`
- [x] `AdminEuNodeController` — `/admin/nodes` CRUD + enroll-страница с curl-командой
- [x] Шаблоны: `eu-nodes.html`, `eu-node-form.html`, `eu-node-enroll.html`, `eu-node-detail.html`
- [x] `XrayConfigMaterializer.injectEuOutbound()` — добавляет `eu` outbound, healthcheck-inbound (SOCKS :10808) и routing в материализуемый конфиг
- [x] `TunnelHealthCheckService` — активная проверка туннеля раз в 5 минут через SOCKS :10808, порог 3 подряд провала → UNREACHABLE
- [x] Bootstrap-скрипт `resources/scripts/eu-node-setup.sh` — установка Xray, генерация keypair, enroll, systemd, cron, health
- [x] `resources/scripts/pohr-agent.sh` — polling конфига каждые 5 минут, health push
- [x] Первая реальная EU-нода развёрнута и работает (RU → EU → интернет)
- [x] RU↔EU-туннель проверен: **`last_tunnel_ip = EU-IP`** в UI ноды
- [x] **Синхронизация версии Xray RU↔EU** через заголовок `X-Pohr-Xray-Version` в `/api/nodes/{id}/config`
- [x] `pohr-agent.sh` умеет сам обновлять Xray (`update_xray()`): читает версию из заголовка, при расхождении скачивает с GitHub, валидирует, подменяет бинарник через systemd
- [x] `pohr-agent.sh` сохраняет ETag в `/etc/pohr/config.etag` вместо локального sha256 — прекращены ложные рестарты Xray каждые 5 минут
- [x] `pohr-agent.sh` не падает на `xray version | head -1` под `set -o pipefail` (SIGPIPE обёрнут через `|| true` + `awk 'NR==1 {print $2}'`)

### HTTPS / Reverse proxy

- [x] Caddy установлен на RU-сервере, HTTPS на `https://pohr-roclh.xyz`
- [x] Let's Encrypt сертификаты для `pohr-roclh.xyz` и `www.pohr-roclh.xyz`, автопродление
- [x] HTTP → HTTPS редирект
- [x] Порт 8080 закрыт наружу, Caddy проксирует через `127.0.0.1:8080`
- [x] Порт 8443 открыт наружу для VPN-клиентов и EU-нод
- [x] `POHR_PUBLIC_URL=https://pohr-roclh.xyz` в `.env`
- [x] `server.forward-headers-strategy: framework` в `application.yml` — `request.getScheme()`/`getServerName()`/`getServerPort()` уважают `X-Forwarded-*` от Caddy
- [x] `PublicUrlResolver` — единая точка сборки публичного URL, приоритет `pohr.public-url` > заголовки

### Deployment

- [x] Multi-stage `Dockerfile` — GraalVM builder + Debian runtime
- [x] `install.sh` — установка Docker (fallback на static binary для EOL Debian), генерация `.env`, запуск контейнера
- [x] Fat Docker image (665 МБ на диске, 168 МБ сжатый) собирается, запускается за 2 сек
- [x] `docker-compose.prod.yml` с env-переменными
- [x] Dockerfile builder — BuildKit cache mounts для `~/.gradle` и wrapper (повторные сборки ~5–15 сек вместо минут)

### Хостинг / инфраструктура

- [x] RU-сервер: Selectel, Debian 11 (EOL), 20 ГБ, 45.131.43.6
- [x] EU-сервер: 78.17.145.160, Debian 11 (EOL), SSH на порту **6155** (не 22!)
- [x] Домен `pohr-roclh.xyz` куплен через Reg.ru, A-запись на RU-IP
- [x] nftables вместо iptables на EU (iptables не установлен)
- [x] Docker volume `pohr-xray` — 15 ГБ legacy Xray-логов удалено с RU, logrotate для journald

---

## 🚧 Что предстоит сделать

### Приоритет 0: довести EU-ноды до прода

- [ ] **Материализация при создании подписки.** `getOrCreate` пишет UUID в БД, но Xray процесс не видит его до рестарта. Добавить `materialize() + restart` при первом создании подписки.
- [ ] **SNI-мультиплексирование** (переехало из P1, стало P0) — см. P1.

### Приоритет 1: скрытность (SNI-мультиплексирование)

- [ ] **Xray вернуть на 443, Caddy переехать на 8444, вход на 443 — через Nginx stream с `ssl_preread`.** Сейчас Xray на 8443 = палево для DPI (нестандартный порт для HTTPS). Схема: Nginx на 443 смотрит SNI — если `xfit.ru` → Xray :8443, если `pohr-roclh.xyz` → Caddy :8444 (TLS). Тогда DPI видит: один IP, порт 443, отвечает сертификатом `xfit.ru` — как обычный сайт.
- [ ] **Убрать порт 8443 из iptables на RU** — после SNI-мультиплексирования он не нужен.
- [ ] **Проверить, что Let's Encrypt сертификаты не палят домен** — сейчас `crt.sh` показывает `pohr-roclh.xyz` → `45.131.43.6`. Для паранойи — DNS-01 challenge или не публиковать UI на собственном домене.

### Приоритет 2: стабильность инфраструктуры

- [ ] **Rebuild RU и EU на Debian 12/13.** Debian 11 EOL — репозитории удалены, Docker ставится из static binary, jq/unzip через `archive.debian.org`. Каждая новая установка пакета = новые грабли. Rebuild окупится с первого же обновления.
- [ ] **`logrotate` для Xray-логов на EU.** В `/etc/logrotate.d/xray` настроить daily rotate + 100M maxsize, иначе `/var/log/xray/error.log` вырастет до гигабайт (как было на RU — 15 ГБ).
- [ ] **Ограничить Docker-логи через `/etc/docker/daemon.json`** — `max-size: 10m`, `max-file: 3`. Иначе долгоживущий контейнер сожрёт диск.

### Приоритет 3: надёжность клиентских установщиков

- [ ] **Детект версии v2rayN** (CoreBasicItem → v7.x, иначе v6.x) — уже частично есть для фрагмента.
- [ ] **Проверка запуска v2rayN после правки.** Ждёт 5 сек, проверяет `Get-Process v2rayN`. Если упал — восстанавливает из pristine.
- [ ] **Параметр `-Fragment on|off|keep`** — некоторым провайдерам фрагмент мешает.
- [ ] **Идемпотентный рестарт** — если конфиг содержит ту же подписку с тем же URL, не перезаписывать.
- [ ] **`-Diagnose`** — диагностика без правок: показывает пути, состояние фрагмента и подписки.
- [ ] **Нормализация line endings в `ClientScriptService.write()`.** `.sh` → LF, `.bat`/`.cmd`/`.ps1` → CRLF. Сейчас редактор через UI сохраняет CRLF в `.sh`, из-за чего bash падает на `set -euo pipefail` с ошибкой «invalid option namepefail». Плюс `.gitattributes` в репо.
- [ ] **Убрать дубликаты DTO из `NativeHints`** (три рекорда зарегистрированы дважды в конце файла).

### Приоритет 4: клиентские установщики — расширение

- [ ] **DNS-настройки в v2rayN.** `SimpleDNSItem`: `RemoteDNS=https://cloudflare-dns.com/dns-query`, `DirectDNS=119.29.29.29`, `BlockAAAAQuery`.
- [ ] **TUN-режим.** `TunModeItem.EnableTun`, `Stack=gvisor`.
- [ ] **Системный прокси.** `SystemProxyItem.SysProxyType`, `SystemProxyExceptions`.
- [ ] **Routing rules из БД** — несколько профилей («ru-direct», «ru-blocked-only», «whitelist»). Сейчас хардкод в `RoutingRulesController`.
- [ ] **«Готовый ZIP»** с преднастроенным v2rayN как альтернатива `.bat`.

### Приоритет 5: мобильные клиенты

- [ ] **Android — Happ.** `happ://add/{SUB_URL}` + отдача JSON с routing-правилами.
- [ ] **Android — v2rayNG.** `v2rayng://install-sub/?url={SUB_URL}`.
- [ ] **iOS — Streisand / Karing / Shadowrocket.** Deep-link схемы: `streisand://import/URL#NAME`, `karing://install-config?url=URL&name=NAME`, `sub://URL`.
- [ ] **QR-код на `/home`.** Генерация `vless://` в QR (ZXing).
- [ ] **Страница `/setup`** — deep-link кнопки по платформам.

### Приоритет 6: масштабирование

- [ ] **gRPC API Xray.** `HandlerService.AlterInbound` с `AddUserOperation`/`RemoveUserOperation` — добавление юзеров без рестарта Xray.
- [ ] **Множественные EU-узлы.** Приоритеты, health-check, автоматическое переключение. Сейчас materializer берёт **первую HEALTHY**, при падении активной все юзеры ложатся.
- [ ] **Fallback на freedom при UNREACHABLE EU.** Сейчас EU-outbound всегда. Правильно: если EU недоступна — direct (geoip:ru). Требует динамического роутинга.
- [ ] **Health-check endpoint'ов `/api/health`** — статус EU-узлов, состояние Xray.

### Приоритет 7: UX

- [ ] **Telegram-бот.** `/start`, `/config`, `/qr`, `/status`. Push при обновлении клиентского скрипта.
- [ ] **Смена пароля через UI** (пока только через SQL).
- [ ] **Даты в UI** — обрезать наносекунды (`2026-10-01T14:31:05`).
- [ ] **Страница `/status`** для пользователя: активен ли EU-узел, когда обновлялась подписка.

### Приоритет 8: безопасность

- [ ] **Self-registration с invite-токенами.**
- [ ] **Лимиты трафика и скорости** (через Xray stats API или nftables).
- [ ] **Аудит действий.**
- [ ] **Rate limiting** на `/sub/{token}`.
- [ ] **`nodeSecret` ротация** раз в 30 дней.
- [ ] **Публичный `/api/health`** сейчас permitAll — сузить до минимума информации.

### Приоритет 9: высший пилотаж

- [ ] **GitHub-коммит скриптов из UI.** Кнопка «Push to GitHub» → branch + PR.
- [ ] **Синхронизация скриптов между инстансами Pohr.**

### Общее — native image

- [ ] **Все temporal-поля в DTO для UI — строки, не `Instant`.** Форматировать на бэкенде. Устраняет целый класс reflection-ошибок (см. грабли 65).
- [ ] **Все UUID в DTO — строки.** То же.
- [ ] **Прогнать все страницы приложения подряд с `bash curl` для smoke-теста.** Сейчас часто ломается что-то в неочевидном месте.

## 🛠️ Технический стек

| Слой | Технология |
|---|---|
| Язык | Java 25 (GraalVM for JDK 25) |
| Фреймворк | Spring Boot 4.0.8 |
| Сборка | Gradle 9.4+, Groovy DSL |
| БД | SQLite + Liquibase |
| ORM | Spring Data JPA + Hibernate 7.2.24 |
| UI | Spring MVC + Thymeleaf |
| CSS | Кастомный, без фреймворков |
| WebSocket | Spring WebSocket (raw, без STOMP) |
| Безопасность | Spring Security 7 |
| Native | GraalVM Native Image + `org.graalvm.buildtools.native` 1.1.14 |
| Xray | Xray-core, управление через ProcessBuilder (пока), gRPC (план) |
| Lombok | 1.18.42 (совместим с JDK 25) |
| Jackson | tools.jackson (Jackson 3) для новых классов |

---

## 📐 Правила разработки

### Оформление кода

1. **DTO — только record** в пакете `org.Roclh.model.dto`. Имя: `XxxDto`, `XxxForm`.
2. **Сущности** — в `org.Roclh.model`, Lombok `@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor`.
3. **Репозитории** — интерфейсы в `org.Roclh.repository`, наследуют `JpaRepository<T, UUID>`.
4. **Сервисы** — в `org.Roclh.service` (для доменных: `service`; для Xray — `service.xray`; для EU-нод — `service.node`).
5. **Контроллеры:**
   - `org.Roclh.controller` — пользовательские (`/home`, `/login`, `/sub`).
   - `org.Roclh.controller.admin` — админские (`/admin/**`).
   - `org.Roclh.controller.api` — API для EU-нод (`/api/nodes/**`).
6. **Конфигурации** — в `org.Roclh.config` (для доменных), `org.Roclh.config.xray` (для Xray), `org.Roclh.config.ws` (для WebSocket), `org.Roclh.config.node` (для EU-нод).
7. **Исключения** — `IllegalArgumentException` там, где не нужен кастомный класс.

### Соглашения

- **Все id сущностей — UUID**, хранятся как `VARCHAR(36)` в SQLite.
- **Все `Instant`** хранятся как ISO-8601 строки через `InstantStringConverter`.
- **Все boolean** — `INTEGER` + `@JdbcTypeCode(SqlTypes.INTEGER)`.
- **Все UUID** — `VARCHAR(36)` + `@JdbcTypeCode(SqlTypes.VARCHAR)`.
- **`ddl-auto: validate`**, только Liquibase changesets.
- **Не редактировать применённые changesets.** Только новые.
- **Liquibase: SQL-миграции**, `--liquibase formatted sql`.
- **Xray-конфиг** — JSON, `ObjectMapper`/`JsonNode` из `tools.jackson.databind`.
- **Логи** — `@Slf4j`, никаких `System.out`.
- **i18n** — все сообщения в `messages.properties` и `messages_ru.properties`.

### Профили

- **`dev`** — `xray.home: ./xray`, `xray.install.auto: true`, Thymeleaf `cache: false`.
- **`prod`** — `xray.home: /app/xray`, `SPRING_PROFILES_ACTIVE=prod`, `XRAY_AUTO_INSTALL=false` (Xray вшит в образ).
- **Без профиля** — datasource, jpa, liquibase, server.port.

### Работа с Xray

- **Источник истины — БД, не файл.** `config.json` пишется только через `XrayConfigMaterializer`. Если правишь руками — при следующем `start()`/`restart()` изменения потеряются.
- **Не перезапускать без нужды.** При добавлении пользователей — либо перезапуск (пока), либо gRPC (план).
- **Логи Xray — в WebSocket.** `XrayLogWebSocketHandler.broadcast(line)` вызывается из `XrayProcessManager.appendLog`.
- **Fallback URL для скачивания.** `xray.install.download-url-template` + `xray.install.fallback-url-template`, проверка через HEAD.
- **Один владелец процесса.** Не пытаться «подцепиться» к чужому Xray. При старте — stale-процесс убивается по PID-файлу.

### Профиль разработчика

- **Локально — `./gradlew bootRun --args='--spring.profiles.active=dev'`**, а не `nativeCompile`.
- **Xray в dev-режиме запускается через приложение.**
- **Никогда не коммитить** `build/`, `.gradle/`, `data/`, `xray/bin/`, `xray/config/config.json`, `xray/xray.pid`, `*.db`.

### Правила для новых фич

- **Проверять работу в native image** после каждой крупной фичи.
- **Тестировать на чистой БД.**
- **Не добавлять зависимостей без нужды.**

---

## ⚠️ Известные грабли (не наступить снова)

1. **Lombok < 1.18.42 не работает с JDK 25.** Обязательно 1.18.42+.
2. **Windows: Gradle ищет `native-image.exe`, но GraalVM кладёт `native-image.cmd`.** `nativeImageCapable = false` + явный путь в `org.gradle.java.installations.paths`.
3. **`.properties` файлы читаются в ISO-8859-1.** Русский текст — через Unicode-escape или IDE с правильной кодировкой.
4. **`spring.factories` для `EnvironmentPostProcessor` в Spring Boot 4 не работает** — инициализация в `main()` до `SpringApplication.run`.
5. **`Path.of("/app/xray")` на Windows** превращается в `\app\xray` — корень диска C:.
6. **`@PostConstruct` может сработать позже `DataSource`** — папку `data/` создавать в `main()`.
7. **SQLite не создаёт родительские директории.** `Files.createDirectories` до первого подключения.
8. **SQLite не имеет `TIMESTAMP WITH TIME ZONE`.** ISO-8601 строки + `InstantStringConverter`.
9. **SQLite не имеет `UUID`.** `VARCHAR(36)` + `@JdbcTypeCode(SqlTypes.VARCHAR)`.
10. **SQLite не имеет `BOOLEAN`.** `INTEGER` + `@JdbcTypeCode(SqlTypes.INTEGER)`.
11. **Hibernate 7 строго валидирует типы.**
12. **`ProcessBuilder("unzip", ...)` на Windows не работает.** `java.util.zip.ZipInputStream`.
13. **`Path.normalize()` без `toAbsolutePath()`** ломает `startsWith` (Zip Slip).
14. **WebSocket `session.sendMessage()` может кинуть `IllegalStateException`** между `isOpen()` и отправкой. Ловить `Exception`.
15. **v2rayN: тип конвертации подписки — обязательно «v2ray»**, иначе `Arg_TimeoutException`.
16. **v2rayN: URL подписки с `localhost` не работает на Windows.** Использовать `127.0.0.1`.
17. **VLESS без TLS/Reality DPI режет мгновенно.**
18. **`th:disabled="${running}"`** работает как HTML-атрибут.
19. **`th:text="${successMessage}"` для i18n** — оборачивать в `#{...}`.
20. **`nativeCompile` может сожрать 10 ГБ RAM.** `-J-Xmx4g --parallelism=2` на слабой машине.
21. **Xray x25519 изменил формат вывода.** Старый: `Private key:` / `Public key:`. Новый (25.x): `PrivateKey:` / `Password (PublicKey):`. Парсер должен поддерживать оба, `Hash32:` игнорировать.
22. **xhttp + Reality несовместим с `flow=xtls-rprx-vision`.** Flow добавлять в ссылку только при `network=tcp`. На сервере для xhttp `flow` в `clients[]` не указывать.
23. **`realitySettings.serverNames` может быть массивом.** В vless-ссылку идёт `serverNames[0]`, но в JSON конфига массив можно оставить.
24. **`realitySettings.fingerprint` / `spiderX` / `alpn`** — читаются из JSON конфига, если их нет — дефолт `chrome` / пусто / пусто. Не хардкодить.
25. **UUID-колонка должна быть `unique`** — иначе при бэкфилле после миграции возможны коллизии. Использовать SQLite-генератор с `randomblob`.
26. **`ObjectMapper.writerWithDefaultPrettyPrinter()`** в Jackson 3 печатает `"key" : value` с пробелом до двоеточия. Xray это переваривает, но для человекочитаемости — свой `DefaultPrettyPrinter` с `DefaultIndenter("  ", "\n")`.
27. **PID-файл `xray/xray.pid`** — не коммитить, в `.gitignore`. При `@PostConstruct` stale-процесс убивается до первого `start()`.
28. **v2rayN 7.x переехал `guiNConfig.json` в `guiConfigs/`.** В 6.x файл лежал в корне рядом с `v2rayN.exe`, в 7.x — в подпапке `guiConfigs\guiNConfig.json`. Поиск должен проверять оба пути (+ `config\`, `configs\` для перестраховки).
29. **v2rayN 7.x — другой формат фрагмента.** Поля `fragmentItem` **не существует**. Вместо него: `CoreBasicItem.EnableFragment: true` + `Fragment4RayItem: { Packets, Lengths[], Delays[], MaxSplit, ... }`. Детектить версию по наличию `CoreBasicItem`.
30. **`Fragment4RayItem.Lengths` и `Delays` — массивы строк**, не строка. `["100-200"]`, не `"100-200"`.
31. **`chcp 65001` в .bat работает нестабильно** на старых Windows и ломается при BOM в файле. Надёжнее — отдавать `.bat` в **Cp866** (`chcp 866 >nul` + `getBytes(CP866)`), а .ps1 в **UTF-8 + BOM**.
32. **PowerShell 5.1 читает `.ps1` без BOM как cp1251.** Кириллица в `Write-Host` сломается. Решение: BOM добавлять в контроллере (`"\uFEFF" + rendered`), файл на диске держать UTF-8 без BOM.
33. **`Set-Content -Encoding UTF8` в PS 5.1 добавляет BOM.** Для `guiNConfig.json` это ок, но для наших собственных скриптов — нет.
34. **`return` из `ForEach-Object` не прерывает цикл в PS 5.1.** Для прерывания использовать флаг (`$found`) и `return` внутри блока, а внешний цикл проверяет `if ($found) { return $found }`.
35. **`Get-Process v2rayN` даёт точный путь к exe**, но не к `guiNConfig.json` — их надо искать отдельно, они могут быть в разных папках в v7.x.
36. **Pristine-бэкап должен создаваться только один раз**, до первой модификации.
37. **`curl | sudo bash` скрипт должен быть идемпотентным.** При повторном запуске на уже-настроенной ноде должен пройти cleanup и не сломать SSH. Проверять правило firewall по порту Xray, а не по общему счётчику.
38. **`iptables -F` в bootstrap-скрипте — СТОП.** Не делать общий flush, только точечное удаление правил на порт Xray (`iptables -D INPUT -p tcp --dport 8443 -j ACCEPT`). Иначе отрежется SSH.
39. **`xray x25519` возвращает ключи в формате, зависящем от версии** — см. п.21. В bash-парсере использовать `grep -E 'PrivateKey|Private key' | awk '{print $NF}'`.
40. **`__USE_LOCAL__` должен быть уникальной строкой** и заменяться через `sed` только в поле `privateKey`. Иначе рискуем заменить в другом месте.
41. **`jq` может отсутствовать на чистом Debian/Ubuntu.** Устанавливать в bootstrap'е (`apt-get install -y jq` с `DEBIAN_FRONTEND=noninteractive`).
42. **EU за NAT.** Если EU-VPS за NAT, `curl api.ipify.org` вернёт публичный IP, но входящие соединения могут не доходить. Для MVP считаем, что у EU есть публичный IP.
43. **`systemctl restart xray` рвёт активные соединения.** При обновлении конфига через agent — предупреждать пользователей (в /home и в TG), но не блокировать.
44. **Docker Desktop: WSL-дистрибутив `docker-desktop` может отсутствовать.** Диагностика показывает `WSL Distribution docker-desktop is missing`. Решение: сброс через Troubleshoot → Reset to factory defaults; если reset падает (`failed to clean up distro Debian`) — `wsl --unregister docker-desktop` вручную, потом Reset.

45. **`wsl --update` код 1618 (`ERROR_INSTALL_ALREADY_RUNNING`).** Windows Installer считает, что другая установка идёт. Решение: перезагрузка, или `net stop msiserver && net start msiserver`, или `wsl --update --web-download`.

46. **`get.docker.com` отказывается ставить Docker на EOL-дистрибутивы (Debian 11).** Решение: ручная установка через `download.docker.com/linux/debian` (поддерживает bullseye), или static binary из `download.docker.com/linux/static/stable/x86_64/` (`docker-27.3.1.tgz` + systemd unit).

47. **Docker на маленьком VPS (20 ГБ) забивает диск распаковкой образа.** Наш образ ~170 МБ сжатый = 665 МБ на диске после `docker pull`. Обязательно `/etc/docker/daemon.json` с `max-size: 10m`, `max-file: 3`.

48. **Native image не включает нестандартные Charset'ы.** `Charset.forName("Cp866")` падает с `UnsupportedCharsetException`. Решение: `-H:+AddAllCharsets` в `buildArgs`. **Не пытаться регистрировать charsets точечно через `registerTypeIfPresent` — не работает (классы `sun.nio.cs.ext.*` отсутствуют в community JDK).**

49. **`get.docker.com` EOL-проверка — на Debian 11 не работает.** Решение: ручная установка Docker.

50. **`/var/log/xray` без ротации может занять весь диск.** У legacy Xray `access.log` и `error.log` пишутся без ограничения. На 20 ГБ VPS за пару лет легко достигает 15 ГБ. **Всегда** настраивать logrotate: `/etc/logrotate.d/xray` с `daily`, `rotate 7`, `maxsize 100M`, `copytruncate`.

51. **systemd-journal тоже не имеет лимита.** `/var/log/journal` может вырасти до гигабайт. Ограничивать через `/etc/systemd/journald.conf.d/size.conf`: `SystemMaxUse=200M`, `MaxRetentionSec=2week`.

52. **Debian 11 EOL — security-репы удалены, но есть `archive.debian.org`.** `security.debian.org` возвращает 404 на все пакеты. Решение: заменить `sources.list` на `archive.debian.org/debian` и `archive.debian.org/debian-security`, отключить `Acquire::Check-Valid-Until "false"`.

53. **`Stream.toList()` возвращает immutable list → SpEL reflection падает в native image.** `java.util.ImmutableCollections$ListN` не зарегистрирован. Шаблоны `th:if="${list.isEmpty()}"` падают с `Method 'isEmpty' cannot be found`. **Решения:**
    - Заворачивать в `new ArrayList<>(...)` перед передачей в модель.
    - В шаблонах использовать `#lists.isEmpty(x)` вместо `x.isEmpty()`.
    - `MemberCategory.INVOKE_PUBLIC_METHODS` для `org.thymeleaf.expression.Lists`.

54. **`java.time.Instant.toString()` требует reflection-регистрации в native image.** Любое `${obj.instant().toString()}` падает с `MissingReflectionRegistrationError`. **Правильное решение: в DTO передавать строки, не `Instant`.** Тогда reflection для java.time в шаблонах не нужен. `MemberCategory.values()` для `java.time.Instant` работает, но не надёжно (`registerTypeIfPresent` для JDK-классов иногда не срабатывает — использовать прямые ссылки `registerType(Instant.class, ...)`).

55. **Thymeleaf expression objects (`#strings`, `#numbers`, `#lists`, ...) требуют reflection-регистрации в native image.** Все 13 классов `org.thymeleaf.expression.*` с `MemberCategory.INVOKE_PUBLIC_METHODS`.

56. **Любой DTO, рендерящийся в шаблоне, требует `MemberCategory.values()` в NativeHints.** Иначе SpEL не найдёт методы (`Method username() cannot be found on type SubscriptionDto`).

57. **`${obj.someString().toLowerCase()}` в Thymeleaf-шаблоне — тоже reflection.** Если на `someString()` вернётся не String, а что-то другое — падение. Использовать `#strings.toLowerCase(obj.someString())`.

58. **`xray version | head -1` с `set -o pipefail` может упасть с SIGPIPE.** `head` читает первую строку и закрывает пайп → `xray version` получает SIGPIPE (exit 141) → `set -e` убивает весь скрипт молча. **Решение:** `cmd 2>/dev/null | head -1 || true`, или `set +o pipefail` перед таким вызовом, или через промежуточную переменную.

59. **Bash `set -e` без trap убивает скрипт без сообщения.** Если bootstrap падает молча — запускать `bash -x` для трассировки, или добавить `trap 'echo "FAILED at line $LINENO"' ERR`.

60. **CRLF в `.sh` ломает bash.** `set -euo pipefail\r` парсится как `set -euo pipefail<CR>`, bash падает с `invalid option namepefail`. Проверка: `cat -A file.sh | head -3` — должно быть `$`, не `^M$`. **Решение:** `sed -i 's/\r$//' file.sh` или `dos2unix`. **Профилактика:** нормализовать line endings в `ClientScriptService.write()` по расширению: `.sh` → LF, `.bat`/`.cmd`/`.ps1` → CRLF. Плюс `.gitattributes` в репо.

61. **Xray требует `geosite.dat` и `geoip.dat` для правил `geosite:*`/`geoip:*`.** Если файлов нет — Xray **полностью падает** при старте с `failed to load geosite: CATEGORY-RU`, а не игнорирует правило. **Решение:** скачивать в volume при первом старте (`XrayAutoInstaller`) или вшивать в образ. Скачивать из `https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/`.

62. **Materializer берёт только HEALTHY/DEGRADED ноды → замкнутый круг при падении Xray.** Если Xray падает (из-за отсутствия geosite.dat), healthcheck ставит UNREACHABLE, и materializer на следующем restart не добавляет EU-outbound → Xray стартует без EU → туннель не работает → UNREACHABLE. **Решение:** брать `findFirst()` из всех нод, любой статус.

63. **Xray на нестандартном порту (8443) палит VPN.** Reality маскируется под обычный HTTPS на 443. Если Xray на 8443 — DPI видит нестыковку. `xray` сам пишет `WARNING: REALITY: Listening on non-443 ports may get your IP blocked by the GFW`. **Решение:** SNI-мультиплексирование (Nginx stream с `ssl_preread`, HAProxy, или Caddy layer4). Один порт 443, прокси смотрит SNI, роутит на Xray или Caddy.

64. **Let's Encrypt логирует все сертификаты в `crt.sh`.** Если хочешь скрыть домен от DPI/РКН — DNS-01 challenge, wildcard, или не публиковать UI вообще (SSH-туннель к localhost). Для персонального использования — не критично.

65. **`WebController.home` собирает URL подписки из `request.getServerPort()`, `request.getScheme()` — это внутренние значения (8080, http), а не публичные.** За Caddy выходит `http://pohr-roclh.xyz:80/sub/...`. **Решение:** читать `X-Forwarded-Proto` и `X-Forwarded-Host`, или использовать `POHR_PUBLIC_URL` из конфига как приоритет.

66. **`XrayConfigService.parseLinks` должен фильтровать по UUID подписки.** Иначе каждая подписка получает ссылки на **все** UUID из конфига (включая чужие) — клиент путается и половину отбрасывает. UUID для ссылки берётся из `subscription.getXrayUuid()`. Inbound пропускается, если UUID не найден в `clients[]`.

67. **Jq может отсутствовать на чистом Debian.** `apt-get install -y jq` на EOL-системах может падать с 404. Решение: static binary с `github.com/stedolan/jq/releases` или через archive.debian.org.

68. **`nft` вместо `iptables` на свежих системах.** Debian 12+ и минимальные LXC иногда не имеют iptables. Проверять `which nft ufw firewall-cmd iptables`. Для nft: `nft add table inet filter` + chain + rules. **Порт SSH может быть нестандартным** (`grep Port /etc/ssh/sshd_config`) — не открывать по-умолчанию 22.

69. **Порт SSH может быть нестандартным.** На EU-сервере SSH на 6155, не на 22. Всегда проверять `ss -tlnp | grep sshd` перед настройкой файрвола.

70. **Редактор скриптов в UI сохраняет CRLF как есть.** `ClientScriptService.write()` не нормализует line endings. Правка `.sh` через UI ломает скрипт. **Решение:** нормализация по расширению в `write()` + `.gitattributes`.
71. **`ARG` не наследуется между стадиями Dockerfile.** `ARG APP_HOME=/app` в стадии `builder` невидим в `stage-1` — `COPY --from=builder ${APP_HOME}/...` разворачивается в пустую строку и даёт `UndefinedVar: Usage of undefined variable '$APP_HOME'`. **Решение:** либо захардкодить путь, либо объявить `ARG APP_HOME=/app` повторно в целевой стадии.

72. **Gradle в Docker скачивает wrapper и зависимости при каждой сборке.** Проброс `~/.gradle` с хоста — плохо (платформо-зависимо, пухнет контекст). **Решение:** BuildKit cache mounts:
Требует BuildKit (Docker 23+; в Docker Desktop включён).

73. **COPY --from=builder с путём, содержащим пробелы/нестандартные символы.** Старый Dockerfile содержал /build/build/native/nativeCompile/pohr — путь, не совпадающий с WORKDIR builder'а. Проверять: docker buildx build --target builder --load -t pohr-builder . && docker run --rm --entrypoint ls pohr-builder -la /app/build/native/nativeCompile/.
XrayAutoInstaller при XRAY_VERSION=latest форсит реинстал при каждом старте контейнера. Ручной апдейт через UI сбрасывается. Решение: ставить только если !installer.isInstalled() или XRAY_VERSION задан явно (не пустой, не latest) и отличается от установленной. Иначе — no-op.

74. explicitVersion.equals(...) — компиляционная ошибка. explicitVersion — boolean, а не строка. Проверять version.equals(installer.installedVersion()), а explicitVersion использовать только как флаг.

75. Xray-процесс не видит новых клиентов после создания подписки. SubscriptionService.getOrCreate пишет xrayUuid в БД, но materialize() не вызывается до ближайшего start()/restart(). Свежесозданный UUID не попадает в clients[] в файле Xray → VLESS-handshake отбрасывается с EOF в клиентских логах. Решение: в WebController.home после getOrCreate — if (!existed) { materializer.materialize(); processManager.restart(); }. Аналогично в AdminSubscriptionController.create.

76. Синхронизация версии Xray EU→RU — только через header, не через GitHub API. Агент на EU не должен вызывать https://api.github.com/... (rate-limit, рассинхрон с RU). RU отдаёт свою установленную версию в X-Pohr-Xray-Version — EU выравнивается на неё.

77. xhttp + Reality несовместим между разными версиями Xray. Клиент 26.9.30 ↔ сервер 26.3.27 даёт Post "https://dns.google/dns-query": EOF и delay -1 ms. XHTTP менялся между этими релизами. Правило: версия Xray на клиенте, RU и EU должна совпадать. Синхронизация — автоматическая через pohr-agent.sh.

78. sniffing.destOverride в inbound на xhttp может ломать handshake. Если после апдейта Xray ничего не помогло — убрать sniffing из inbound, попробовать mode: auto вместо stream-up, убрать scMaxConcurrentPosts.

79. Caddy на 443 → server.forward-headers-strategy: framework обязателен, иначе URL подписки — http://host:80/sub/.... Spring MVC по умолчанию читает scheme/host/port из ServletRequest (внутренние 8080/http), а не из X-Forwarded-*.

80. EU_XRAY_VERSION в .env теперь конфликтует с ручным апдейтом на RU. Если задан XRAY_VERSION=26.9.30 в .env — RU форсит пин при каждом старте, EU подтянет ту же. Если хочется управлять версией только через UI — не задавать XRAY_VERSION / оставить latest.

81. При синхронизации версии: не откатывать EU до старой версии, если на RU временно устаревшая. В pohr-agent.sh при mismatch всегда тянет версию с RU — даже если она старше. Правильно для MVP (выравнивание), но при откате на RU EU тоже откатится. Для защиты от отката — guard в update_xray() (сравнивать semver и не откатываться).


---

## 📋 Открытые вопросы

1. **SNI-мультиплексирование** — Nginx stream или Caddy layer4? Nginx уже установлен, но не используется. Проще Nginx stream.
3. **Fallback на freedom при UNREACHABLE EU.** Сейчас все клиенты ложатся. Динамический роутинг — задача.
4. **gRPC Xray** — до или после мобильных клиентов? Если после EU — при добавлении юзера нужен рестарт.
5. **Rebuild на Debian 12** — когда? Каждая новая установка пакета = грабли.
6. **Routing rules из БД** — делать или хардкод в коде хватит?
7. **Единый формат клиентских конфигов** — абстрактный профиль в БД или per-client скрипты?
8. **Обновление клиентских скриптов у пользователей** — как уведомить? TG-бот, email, баннер на `/home`?
9. **Android без рута** — только подписка + deep-link, или ADB (нереалистично)?
10. **Мультитенантность** — один админ или несколько?
11. **PostgreSQL** — когда нужен, при каком размере?
12. **Ротация subscription-токенов** — ручная или авто раз в N дней?
13. **`nodeSecret` ротация** — раз в 30 дней автоматически?
14. **Backup приватного ключа EU** — напоминание в UI ноды, или автобэкап на RU?
15. **Xray на не-443 порту** — срочно делать SNI-mux или отложить?
16. Ротация Xray версий. Сейчас EU выравнивается на RU автоматом. Что если новая версия на RU сломает xhttp, а откат уже сделали на EU? Нужен ли guard semver в update_xray()?
17. Материализация при getOrCreate подписки. Сейчас требует restart Xray процесс. gRPC AlterInbound снимет это ограничение.
18. sniffing в RU-inbound — оставить или убрать? Помогает для routing по домену, но может мешать на xhttp.

---

## 🎬 Как начать новый диалог

Привет. Продолжаем разработку **Pohr** — системы управления VPN-инфраструктурой для обхода блокировок РКН.

Текущий статус (см. полный контекст ниже):
Сделано: Xray-конфиги в БД + материализация, per-user UUID, xhttp+Reality, редактор конфигов, клиентские скрипты в volume с seed'ом из бандла, рабочий v2rayN-установщик (фрагмент, per-user подписка, pristine-бэкапы, -Restore).

Сделано: EU-ноды — enrollment API, bootstrap-скрипт, polling+health, admin UI, fat Docker image, автоматическая синхронизация версии Xray RU→EU.

Сделано: HTTPS через Caddy, PublicUrlResolver, forward-headers, download geosite/geoip, materializer fallback на UNREACHABLE.

Работает end-to-end: клиент → RU-мост → EU-узел → интернет через Reality (TCP/xhttp).

Предстоит (P0): материализация при создании подписки, SNI-мультиплексирование (Nginx stream + ssl_preread, Xray на 443, Caddy на 8444).

Предстоит: rebuild на Debian 12, gRPC Xray, мобильные клиенты (Happ/v2rayNG/Streisand/Karing/Shadowrocket), routing rules в БД, TG-бот.
Стек: Java 25 + Spring Boot 4.0.8 + GraalVM Native + SQLite + Liquibase + Thymeleaf + Spring Security + Xray-core.

Правила: DTO — record в `model/dto`, сущности в `model`, Liquibase через SQL-миграции, `ddl-auto: validate`, id — UUID, время — `Instant` через `InstantStringConverter`, **Xray-конфиг в БД, файл — материализация**, **клиентские скрипты в volume с seed'ом из бандла, редактор в UI**, **EU-ноды — pull-модель, EU генерит свои ключи локально, RU видит только publicKey**.
Версия Xray синхронизируется RU→EU автоматом. Если правишь версию через UI — EU подтянет на следующем поллинге (≤5 мин). Ручное изменение версии на EU бессмысленно — будет перезаписано. Для точечного апгрейда используй UI на RU.
Логи пишутся в файл через XrayProcessManager; WebSocket broadcast — только для UI. Никаких прямых записей в /app/xray/*.log.

Следующий шаг: [что делаем].

[вставить полный документ]

---

## 🔑 Итоговый принцип

**Pohr — это не «ещё один VPN-сервер». Это платформа для администратора, который хочет:**
- тратить 5 минут на развёртывание, а не 5 часов;
- не думать о блокировках — система сама переключает узлы;
- выдавать подписки одним кликом;
- редактировать конфиг Xray в удобном редакторе;
- прятаться от DPI через Reality и от провайдера VPS через маскировку процессов и трафика.

**Всё в проекте подчинено этим целям. Любая новая фича проверяется вопросом: «Это упрощает жизнь админа или усложняет? Работает ли это в условиях блокировок? Не палит ли это систему?»**