# Pohr — система управления VPN-инфраструктурой

## 📌 Что это

**Pohr** (`roclh/pohr`) — self-hosted платформа для раздачи VPN-доступа через подписки. **RU-мост** (зеркало, оркестратор, входная точка, UI) + **EU-узел** (выходной Xray). Отказоустойчивая замена ручной настройки VPN в условиях блокировок РКН.

**Цель:** «нажал кнопку — всё работает», скрытно от провайдера VPS и РКН, с универсальным конфигурированием через web UI.

**Владелец:** Roclh (GitHub/Docker Hub — `roclh`).

---

## 🎯 Целевая картинка продукта

1. **Простота развёртывания.** Один скрипт поднимает всё на RU. EU-узел — одной curl-командой.
2. **Скрытность.** Xray маскируется под безобидный процесс, трафик — под TLS через Reality.
3. **Универсальность.** Админ через UI правит Xray-конфиг, юзеров, подписки, обновляет Xray.
4. **Подписки** как универсальный механизм (v2rayN, Happ, v2rayNG, Karing, Streisand, Shadowrocket).
5. **Отказоустойчивость.** При блокировке EU — новый узел, клиентский конфиг не меняется.
6. **Низкое потребление.** Native image ~60–140 МБ RSS, помещается на VPS 512 МБ.
7. **Минимум ручной работы.** Подписки создаются автоматически, админ занимается инфраструктурой.

---

## 🏗️ Архитектура

```
Клиент → RU-мост (VPS, Spring Boot Native + UI) → EU-узел (Xray) → интернет
```

**Роли:** RU — UI/API/SQLite/оркестрация. EU — Xray, принимает трафик от RU. SQLite — users, subscriptions, configs, nodes, invites, client-configs, metrics.

### Ключевые архитектурные идеи

- **Xray-конфиг в БД**, файл `xray/config/config.json` — материализация активного конфига + инъекция клиентов из активных подписок. Материализация: при старте, перед каждым start/restart, по событию `SubscriptionsChangedEvent`. Позволяет A/B, откат одним кликом.
- **Клиентские скрипты в volume.** Бандл в образе (`classpath:scripts/*`) — эталон, volume `SCRIPTS_HOME` — рабочий, seed при первом старте. Правки админа переживают пересборку, `.history/` до 10 версий.
- **EU-ноды — pull-модель.** EU генерит Reality-ключи локально, RU видит только publicKey. EU polling `/api/nodes/{id}/config` каждые 5 мин, health push. Версия Xray синхронизируется RU→EU через заголовок `X-Pohr-Xray-Version`.
- **SNI-мультиплексирование.** Nginx stream на :443 с `ssl_preread` — SNI `xfit.ru` → Xray :8443, SNI `pohr-roclh.xyz` → Caddy :8444. DPI видит один IP :443 с валидным сертификатом `xfit.ru`. Порт 8443/8444 закрыты снаружи через iptables.
- **Инвайты.** `InviteToken` (одноразовый, TTL), принимается через `/invite/{token}` → автосоздание user + subscription + автологин.
- **Client configs.** Таблица `client_configs` — HTTP-заголовки подписки per User-Agent. `SubscriptionController` выбирает по самому длинному совпадению.
- **Мониторинг.** `metric_samples` — time-series (1 точка/мин, retention 7 дней): pod cgroup CPU/RAM, JVM heap/threads, Xray RSS/CPU/IO, EU-node статус. Графики — inline SVG.

---

## ✅ Что сделано

**Инфраструктура:** Monorepo Gradle, Java 25, SB 4.0.8, GraalVM Native (~98 МБ бинарь, старт 60 мс), Docker multi-stage, профили dev/prod, deploy через `install.sh`.

**БД:** SQLite + Liquibase (SQL-миграции 001–008), `ddl-auto: validate`, `InstantStringConverter`, `JdbcTypeCode` для UUID/boolean/INTEGER.

**Модели:** `User`, `Subscription` (с `xrayUuid`), `XrayConfig`, `EuNode`, `EnrollmentToken`, `InviteToken`, `ClientConfig`, `MetricSample`.

**UI:** Login, `/home` (панели импорта + advanced block), `/admin` (grid карточек), CRUD users/subscriptions/invites/nodes/configs/scripts/client-configs, `/admin/xray` (live-логи через WS + фильтр по клиенту), `/admin/monitoring` (сводка + графики 24ч + EU-ноды). Breadcrumbs-фрагмент. RU/EN i18n через cookie. Адаптив под мобильные.

**Xray:** Installer с fallback, `XrayProcessManager` (PID-файл + stale-recovery), `XrayRealityService` (x25519 в обоих форматах), `XrayConfigService` (build/parse vless-ссылок с fp/spx/alpn/flow из JSON), `XrayConfigMaterializer` (inject клиентов + EU-outbound + healthcheck-socks + routing), `XrayAssetService` (geosite/geoip).

**Подписки:** `GET /sub/{token}` — Base64 vless-ссылок, headers Profile-Title/Update-Interval/Userinfo, URL собирается через `PublicUrlResolver`. `/sub/{token}/rules.json` для v2rayN. Per-user UUID. Автосоздание при первом `/home`. Материализация при изменении через `SubscriptionsChangedListener` (AFTER_COMMIT → materialize + restart).

**EU-ноды:** Enrollment API (`/api/nodes/bootstrap.sh`, `/register`, `/{id}/config`, `/{id}/health`, `agent.sh`), admin UI, bootstrap-скрипт, pohr-agent.sh (polling + sync версии + health). `TunnelHealthCheckService` через SOCKS :10808, 3 фейла → UNREACHABLE.

**Клиентские скрипты:** `v2rayn-setup.bat` + `.ps1` (поиск v2rayN по 7 уровням, guiNConfig v6/v7, pristine-бэкапы, фрагмент, `-Restore`). `ClientScriptService` с per-extension кодировкой (Cp866 / UTF-8+BOM).

**HTTPS:** Caddy с LE-сертификатами, `forward-headers-strategy: framework`, Nginx stream для SNI-мультиплексирования.

**Инвайты:** Полный цикл — создание токена, страница enroll, `/invite/{token}` с формой accept, автологин через `HttpSessionSecurityContextRepository`.

**Клиентские заголовки:** Редактор в `/admin/client-configs`, `SubscriptionController` выбирает по User-Agent (longest match wins). Seed-дефолты для happ/v2rayng/v2rayn.

**Мониторинг:** `MetricsCollector` (cgroup v1/v2 + /proc + JVM), `MetricsService.toSvgPath`, страница с плитками и графиками. `ContainerMetrics` детектит container vs host.

**Хостинг:** RU — Selectel, Debian 11 (EOL), 45.131.43.6. EU — 78.17.145.160, Debian 11 (EOL), SSH :6155. Домен `pohr-roclh.xyz` (Reg.ru).

---

## 🚧 Что предстоит

### P0 — стабильность и скрытность
- **Rebuild RU/EU на Debian 12/13.** EOL-репы = грабли на каждом шаге.
- **`logrotate` для Xray-логов на EU** (`daily`, `rotate 7`, `maxsize 100M`, `copytruncate`).
- **Docker daemon.json** — `max-size: 10m`, `max-file: 3`.
- **Проверить, что Let's Encrypt не палит домен** — `crt.sh` показывает `pohr-roclh.xyz → 45.131.43.6`. Паранойя: DNS-01, wildcard, или SSH-туннель к UI.

### P1 — надёжность клиентских установщиков
- Детект версии v2rayN (CoreBasicItem → v7).
- Проверка запуска v2rayN после правки (5 сек → `Get-Process` → restore из pristine если упал).
- `-Fragment on|off|keep`, `-Diagnose`.
- Идемпотентный рестарт (не перезаписывать ту же подписку).
- **Нормализация line endings в `ClientScriptService.write()`** (`.sh` → LF, `.bat/.cmd/.ps1` → CRLF) + `.gitattributes`.

### P2 — клиентские установщики (расширение)
- DNS в v2rayN (`SimpleDNSItem`).
- TUN-режим, системный прокси.
- Routing rules из БД (несколько профилей).
- «Готовый ZIP» с преднастроенным v2rayN.

### P3 — мобильные клиенты
- Deep-link: Happ (`happ://add/`), v2rayNG (`v2rayng://install-sub?url=BASE64`), Streisand, Karing, Shadowrocket. Уже частично есть на `/home`.
- **QR-код** на `/home` (ZXing).
- Страница `/setup` с кнопками по платформам.
- **Happ: Provider ID** — без него расширенные параметры (fragmentation, routing) игнорируются. Пользователь должен зарегистрироваться на happ-proxy.com и указать его в подписке.

### P4 — масштабирование
- **gRPC API Xray** (`AlterInbound` с AddUser/RemoveUser) — убирает рестарт при добавлении юзера.
- **Множественные EU-узлы** с приоритетами и автоматическим переключением.
- **Fallback на freedom при UNREACHABLE EU** — сейчас все клиенты ложатся.
- Health-check endpoint `/api/health` с деталями.

### P5 — UX
- Telegram-бот (`/start`, `/config`, `/qr`, `/status`, push при обновлении скрипта).
- Смена пароля через UI.
- Страница `/status` для юзера.

### P6 — безопасность
- Лимиты трафика/скорости (Xray stats API или nftables).
- Аудит действий, rate limiting на `/sub/{token}`.
- Ротация `nodeSecret` раз в 30 дней.
- Сузить публичный `/api/health`.

### P7 — высший пилотаж
- GitHub-коммит скриптов из UI (branch + PR).
- Синхронизация скриптов между инстансами.

---

## 🛠️ Стек

| Слой | Технология |
|---|---|
| Язык | Java 25 (GraalVM) |
| Фреймворк | Spring Boot 4.0.8 |
| Сборка | Gradle 9.4+ |
| БД | SQLite + Liquibase |
| ORM | Spring Data JPA + Hibernate 7.2.24 |
| UI | Spring MVC + Thymeleaf |
| WS | Spring WebSocket (raw) |
| Security | Spring Security 7 |
| Native | GraalVM Native Image + buildtools 1.1.14 |
| Xray | Xray-core, ProcessBuilder (gRPC — план) |
| Lombok | 1.18.42 |
| Jackson | tools.jackson (Jackson 3) |

---

## 📐 Правила разработки

### Оформление

1. **DTO — record** в `org.Roclh.model.dto`. Имя `XxxDto`, `XxxForm`.
2. **Сущности** — в `org.Roclh.model`, Lombok `@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor`.
3. **Репозитории** — `org.Roclh.repository`, `JpaRepository<T, ID>`.
4. **Сервисы** — `service`, `service.xray`, `service.node`, `service.metric`.
5. **Контроллеры:** `controller` (юзерские), `controller.admin`, `controller.api`.
6. **Конфигурации** — `config`, `config.xray`, `config.ws`, `config.node`.
7. **Исключения** — `IllegalArgumentException` где хватает.

### Соглашения

- **Все id сущностей — UUID (`VARCHAR(36)`)**, кроме **time-series** (`MetricSample`): `Long` + `INTEGER PRIMARY KEY AUTOINCREMENT`. Причины: монотонность для prune/`ORDER BY id DESC`, меньше размер индекса, нет FK. Требует `@Column(columnDefinition = "INTEGER")`, иначе Hibernate 7 падает на `validate` (ожидает BIGINT).
- **`Instant`** — ISO-8601 строки через `InstantStringConverter`.
- **boolean** — `INTEGER` + `@JdbcTypeCode(SqlTypes.INTEGER)`.
- **UUID** — `VARCHAR(36)` + `@JdbcTypeCode(SqlTypes.VARCHAR)`.
- **`ddl-auto: validate`**, только Liquibase changesets, не редактировать применённые.
- **Liquibase** — SQL-миграции, `--liquibase formatted sql`.
- **Xray-конфиг** — JSON, `ObjectMapper`/`JsonNode` из `tools.jackson.databind`.
- **Логи** — `@Slf4j`.
- **i18n** — `messages.properties` + `messages_ru.properties`.
- **Нормализация line endings** при записи скриптов: `.sh` → LF, `.bat/.cmd/.ps1` → CRLF.
- **Thymeleaf fragments не вкладывать друг в друга** — иначе параметры внешнего фрагмента не будут переданы во внутренний.
- **`<meta name="viewport">`** обязателен на каждой странице с собственным `<head>` (login.html выпал из-за этого, рендерился в 980px).
- **Не использовать `#httpServletRequest`/`#request`** в шаблонах — недоступны в native, легко ломаются. Передавать из контроллера через `Model`.

### Профили

- **dev** — `xray.home: ./xray`, Thymeleaf `cache: false`.
- **prod** — `xray.home: /app/xray`, `XRAY_AUTO_INSTALL=false`, `SPRING_PROFILES_ACTIVE=prod`.

### Работа с Xray

- **Источник истины — БД.** Правки в файл теряются при следующем restart.
- **Не перезапускать без нужды.** Для добавления юзеров — событие + restart (gRPC в плане).
- **Один владелец процесса** — stale убивается по PID-файлу.
- **Логи через WS** — `XrayLogWebSocketHandler.broadcast` из `XrayProcessManager.appendLog`.
- **Fallback URL** через HEAD, primary → fallback.

### Профиль разработчика

- Локально — `./gradlew bootRun --args='--spring.profiles.active=dev'`.
- Не коммитить: `build/`, `.gradle/`, `data/`, `xray/bin/`, `xray/config/config.json`, `xray/xray.pid`, `*.db`.
- После крупных фич проверять в native image.

---

## ⚠️ Известные грабли

**Java / Lombok / Gradle**
1. Lombok < 1.18.42 не работает с JDK 25.
2. Windows: Gradle ищет `native-image.exe`, GraalVM кладёт `native-image.cmd`. `nativeImageCapable = false` + явный путь.
3. `.properties` — ISO-8859-1, русский через escape или правильную IDE-кодировку.
4. `spring.factories` для `EnvironmentPostProcessor` в SB 4 не работает — инициализация в `main()`.
5. `nativeCompile` может сожрать 10 ГБ RAM. `-J-Xmx4g --parallelism=2`.

**SQLite / JPA**
6. SQLite не имеет `TIMESTAMPTZ`, `UUID`, `BOOLEAN`. ISO-8601 строки + конвертеры + `JdbcTypeCode`.
7. SQLite не создаёт директории — `Files.createDirectories` до подключения.
8. Hibernate 7 строго валидирует типы. Для `Long` id в SQLite требуется `columnDefinition = "INTEGER"`, иначе ожидает `BIGINT` и падает.
9. `@Modifying`-запрос требует `@Transactional` на вызывающем методе.
10. UUID-колонка должна быть `unique` — иначе коллизии при бэкфилле. Использовать `randomblob`.

**Windows / Native**
11. `ProcessBuilder("unzip")` на Windows не работает — `java.util.zip.ZipInputStream`.
12. `Path.normalize()` без `toAbsolutePath()` ломает `startsWith` (Zip Slip).
13. `Path.of("/app/xray")` на Windows → `\app\xray`.
14. Native image не включает нестандартные Charset'ы — `-H:+AddAllCharsets`. Точечная регистрация `sun.nio.cs.ext.*` не работает.
15. `ARG` не наследуется между стадиями Dockerfile — объявлять заново или хардкодить.
16. Gradle в Docker — BuildKit cache mounts вместо проброса `~/.gradle`.
17. `COPY --from=builder` с путём, содержащим пробелы, ломается. Проверять через `--target builder`.

**Native image + Thymeleaf**
18. `Stream.toList()` возвращает immutable — SpEL reflection падает. Оборачивать в `new ArrayList<>()` или использовать `#lists.isEmpty`.
19. `Instant.toString()` требует reflection — в DTO передавать строки.
20. Все 13 `org.thymeleaf.expression.*` требуют `MemberCategory.INVOKE_PUBLIC_METHODS`.
21. Любой DTO, рендерящийся в шаблоне, требует `MemberCategory.values()` в NativeHints.
22. `${obj.someString().toLowerCase()}` — тоже reflection. Использовать `#strings.toLowerCase()`.
23. **`#httpServletRequest` недоступен** в Thymeleaf 3.1 даже в JVM, тем более в native. Передавать флаг из контроллера через `@ModelAttribute`.
24. **Фрагменты не вкладывать друг в друга** — параметры внешнего фрагмента не пробрасываются во внутренний, `#{null}` → `Message key cannot be null`.
25. **`<meta name="viewport">`** обязателен на каждой странице с собственным `<head>` (login.html рендерился в 980px без него, media queries не срабатывали).
26. Thymeleaf `#messages.msg(...)` доступен, `T(java.lang.String).format(...)` доступен.
27. `<th:block>` вместо `<ng-container>` — не оставляет HTML-тег.

**Xray / Reality**
28. x25519 формат менялся: `Private key:` ↔ `PrivateKey:`, `Public key:` ↔ `Password (PublicKey):`. Парсер — оба, `Hash32:` игнорировать.
29. xhttp + Reality несовместим с `flow=xtls-rprx-vision`. Flow только при `network=tcp`.
30. `realitySettings.serverNames` может быть массивом — в ссылку идёт `[0]`.
31. `fingerprint`/`spiderX`/`alpn` — читать из JSON, не хардкодить.
32. Xray требует `geosite.dat`/`geoip.dat` для правил `geosite:*`/`geoip:*` — иначе полностью падает при старте. Скачивать в volume.
33. `xray version | head -1` под `set -o pipefail` падает с SIGPIPE — оборачивать через `|| true` или `awk`.
34. Reality выбирает SNI — не-443 порт палит. `WARNING: REALITY: Listening on non-443 ports`.
35. **`sniffing` в RU-inbound на xhttp ломает handshake** периодически. Убрали из активного конфига. Не возвращать без `routeOnly: true` — этот режим читает SNI только для routing, не переписывает destination.
36. **Версии Xray должны совпадать** на клиенте, RU и EU. XHTTP ломался между 26.3.27 и 26.9.30.
37. `__USE_LOCAL__` в EU-конфиге — уникальная строка, `sed` только в `privateKey`.

**EU-ноды / скрипты**
38. `curl | sudo bash` должен быть идемпотентным. Проверять firewall по порту Xray, не общим счётчиком.
39. `iptables -F` — СТОП. Точечное удаление правил на порт Xray.
40. `jq` может отсутствовать — ставить в bootstrap.
41. EU за NAT — `api.ipify.org` вернёт публичный IP, но входящие могут не доходить.
42. `systemctl restart xray` рвёт соединения.
43. Порт SSH может быть нестандартным (`ss -tlnp | grep sshd`).
44. `nft` вместо `iptables` на Debian 12+.
45. Синхронизация версии EU→RU только через `X-Pohr-Xray-Version` header, не через GitHub API.

**Клиентские скрипты / v2rayN**
46. v2rayN: тип конвертации подписки — «v2ray», иначе `Arg_TimeoutException`.
47. v2rayN: URL с `localhost` не работает. Использовать `127.0.0.1`.
48. v2rayN 7.x переехал `guiNConfig.json` в `guiConfigs/`.
49. v2rayN 7.x формат фрагмента: `CoreBasicItem.EnableFragment` + `Fragment4RayItem.{Packets,Lengths[],Delays[],MaxSplit}`. `Fragment4RayItem.Lengths/Delays` — массивы строк.
50. `chcp 65001` нестабилен — `.bat` в Cp866, `.ps1` в UTF-8+BOM.
51. PS 5.1 без BOM читает `.ps1` как cp1251.
52. `Set-Content -Encoding UTF8` в PS 5.1 добавляет BOM (для наших скриптов плохо).
53. `return` из `ForEach-Object` не прерывает цикл — использовать флаг.
54. `Get-Process v2rayN` даёт exe, но не путь к конфигу.
55. Pristine-бэкап создавать один раз, до первой модификации.

**Docker / Deploy**
56. `get.docker.com` EOL-проверка ломается на Debian 11 — ручная установка.
57. Docker на 20 ГБ VPS забивает диск — `/etc/docker/daemon.json`.
58. `/var/log/xray` без ротации — гигабайты. logrotate обязателен.
59. systemd-journal тоже без лимита — `SystemMaxUse=200M`.
60. Debian 11 EOL — `security.debian.org` 404, использовать `archive.debian.org`.
61. Docker Desktop WSL `docker-desktop` может отсутствовать — reset через Troubleshoot.
62. `wsl --update` код 1618 — перезагрузка или `net stop msiserver`.

**Подписки / URL**
63. `WebController.home` собирает URL из `request.getScheme()` — за Caddy будет `http://host:80/sub/...`. Использовать `PublicUrlResolver` (приоритет `pohr.public-url`).
64. `XrayConfigService.parseLinks` фильтрует по UUID подписки — без этого все получают все UUID.
65. Let's Encrypt логирует всё в `crt.sh`.
66. `server.forward-headers-strategy: framework` обязателен за прокси.
67. URL подписки на Windows с `localhost` не работает.
68. Для v2rayN deep-link — `v2rayng://install-sub?url=BASE64&name=Pohr` (не URL-encoded).

**Happ**
69. Happ расширенные параметры (fragmentation, routing) требуют **Provider ID** от happ-proxy.com. Без него заголовки игнорируются.
70. Happ 5.9.0 имеет баг с `noise` — может ронять handshake.
71. Happ на iOS + xhttp + Reality — fragmentation часто не применяется, ограничение приложения.
72. `fragmentation-enable` и др. передаются через HTTP-заголовки (или тело `#key: value`).
73. Передача параметров: HTTP-заголовки и/или тело `#key: value`.

**UI / CSS**
74. `#httpServletRequest` недоступен — передавать флаги из контроллера.
75. `<meta name="viewport">` обязателен на каждой странице.
76. CRLF в `.sh` ломает bash (`invalid option namepefail`).

---

## 📋 Открытые вопросы

1. **Fallback на freedom при UNREACHABLE EU** — динамический роутинг.
2. **gRPC Xray** — до или после мобильных клиентов?
3. **Rebuild на Debian 12** — когда?
4. **Routing rules из БД** — делать или хардкод?
5. **Обновление клиентских скриптов** — как уведомлять? TG-бот, email, баннер?
6. **Мультитенантность** — один админ или несколько?
7. **PostgreSQL** — когда нужен?
8. **Ротация subscription-токенов** — ручная или авто?
9. **`nodeSecret` ротация** — 30 дней автоматически?
10. **Backup приватного ключа EU** — напоминание или автобэкап на RU?
11. **Ротация Xray версий.** Если новая версия на RU сломает xhttp, а откат уже на EU — нужен ли guard semver в `update_xray()`?
12. **SNI-mux**: `xfit.ru` как target Reality — правильно ли? Может, использовать чужой популярный домен?
13. **Материализация через gRPC** — снимет требование restart Xray при новом юзере.

---

## 🎬 Как начать новый диалог

Привет. Продолжаем разработку **Pohr** — системы управления VPN-инфраструктурой для обхода блокировок РКН.

**Стек:** Java 25 + Spring Boot 4.0.8 + GraalVM Native + SQLite + Liquibase + Thymeleaf + Spring Security + Xray-core.

**Работает end-to-end:** клиент → RU-мост → EU-узел → интернет через Reality (xhttp).

**Сделано:** Xray-конфиги в БД + материализация, per-user UUID, SNI-мультиплексирование через Nginx stream (Xray на 8443, Caddy на 8444), инвайты, client configs (HTTP-заголовки per User-Agent), мониторинг (метрики контейнера/Xray/EU-нод + SVG-графики), адаптивный UI, breadcrumbs, admin grid, deep-link кнопки на `/home`.

**Правила:** DTO — record в `model/dto`, сущности в `model`, Liquibase SQL, `ddl-auto: validate`, id — UUID (кроме time-series — `Long INTEGER`), время — `Instant` через `InstantStringConverter`, **Xray-конфиг в БД, файл — материализация**, **клиентские скрипты в volume**, **EU-ноды — pull-модель**, **sniffing убран из RU-inbound** (ломал xhttp handshake), **`#httpServletRequest` недоступен** (передавать флаги из контроллера), **`<meta viewport>` обязателен на каждой странице**.

**Предстоит (P0):** rebuild на Debian 12, logrotate для Xray-логов, ограничить Docker-логи, проверить LE-сертификаты на палево.

**Предстоит:** мобильные клиенты (Happ с Provider ID, v2rayNG через custom JSON, deep-links), gRPC Xray, множественные EU с fallback, TG-бот, routing rules в БД.

**Следующий шаг:** [что делаем].

[вставить полный документ]

---

## 🔑 Итоговый принцип

**Pohr — это не «ещё один VPN-сервер». Это платформа для администратора, который хочет:**
- тратить 5 минут на развёртывание, а не 5 часов;
- не думать о блокировках — система сама переключает узлы;
- выдавать подписки одним кликом;
- редактировать Xray-конфиг в удобном редакторе;
- прятаться от DPI через Reality и от провайдера VPS через маскировку процессов и трафика.

**Всё подчинено этим целям. Любая новая фича проверяется вопросом: «Это упрощает жизнь админа или усложняет? Работает ли это в условиях блокировок? Не палит ли это систему?»**