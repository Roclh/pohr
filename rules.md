# Pohr — система управления VPN-инфраструктурой

## 📌 Что это

**Pohr** (`roclh/pohr`) — self-hosted платформа для раздачи VPN-доступа через подписки. **RU-мост** (зеркало, оркестратор, входная точка, UI) + **EU-узел** (выходной Xray). Отказоустойчивая замена ручной настройки VPN в условиях блокировок РКН. Дополнительно — **MTProto-прокси для Telegram** (telemt) поверх EU-туннеля.

**Цель:** «нажал кнопку — всё работает», скрытно от провайдера VPS и РКН, с универсальным конфигурированием через web UI.

**Владелец:** Roclh (GitHub/Docker Hub — `roclh`).

---

## 🎯 Целевая картинка продукта

1. **Простота развёртывания.** Один скрипт поднимает всё на RU. EU-узел — одной curl-командой.
2. **Скрытность.** Xray маскируется под безобидный процесс, трафик — под TLS через Reality. Telegram-прокси маскируется под чужой домен (cover domain) через fake-TLS.
3. **Универсальность.** Админ через UI правит Xray-конфиг, юзеров, подписки, обновляет Xray и telemt.
4. **Подписки** как универсальный механизм (v2rayN, Happ, v2rayNG, Karing, Streisand, Shadowrocket).
5. **Отказоустойчивость.** При блокировке EU — новый узел, клиентский конфиг не меняется.
6. **Низкое потребление.** Native image ~60–140 МБ RSS, помещается на VPS 512 МБ.
7. **Минимум ручной работы.** Подписки и TG-секреты создаются автоматически.

---

## 🏗️ Архитектура

```
VPN:      Клиент → RU-мост (Spring Boot Native + UI) → EU-узел (Xray) → интернет
Telegram: Клиент → Nginx :443 (SNI=cover) → telemt :3128 → Xray SOCKS :10809 → EU outbound → TG DC
```

**Роли:** RU — UI/API/SQLite/оркестрация. EU — Xray, принимает трафик от RU. SQLite — users, subscriptions, configs, nodes, invites, client-configs, metrics, telegram-proxy.

### Ключевые архитектурные идеи

- **Xray-конфиг в БД**, файл `xray/config/config.json` — материализация активного конфига + инъекция клиентов из активных подписок + `telemt-socks` inbound (когда TG-прокси включён). Материализация: при старте, перед каждым start/restart, по событию `SubscriptionsChangedEvent`, при изменении конфига telemt.
- **Клиентские скрипты в volume.** Бандл в образе (`classpath:scripts/*`) — эталон, volume `SCRIPTS_HOME` — рабочий, seed при первом старте. Правки админа переживают пересборку, `.history/` до 10 версий.
- **EU-ноды — pull-модель.** EU генерит Reality-ключи локально, RU видит только publicKey. EU polling `/api/nodes/{id}/config` каждые 5 мин, health push. Версия Xray синхронизируется RU→EU через заголовок `X-Pohr-Xray-Version`.
- **SNI-мультиплексирование (Nginx stream).** На :443 `ssl_preread` разводит по SNI: `xfit.ru` → Xray :8443, `pohr-roclh.xyz` → Caddy :8444, `<cover-domain>` (например `dodopizza.ru`) → telemt :3128. DPI видит один IP :443 с валидными сертификатами.
- **Telegram MTProto (telemt).** Отдельный процесс, слушает :3128, весь исходящий трафик отправляет через SOCKS5-inbound Xray (`127.0.0.1:10809`). Xray routing гонит `telemt-socks` → `eu` outbound. Per-user секреты в `telegram_proxy_users` (1:1 с `users`, CASCADE).
- **Инвайты.** `InviteToken` (одноразовый, TTL), принимается через `/invite/{token}` → автосоздание user + subscription + автологин.
- **Client configs.** Таблица `client_configs` — HTTP-заголовки подписки per User-Agent. `SubscriptionController` выбирает по самому длинному совпадению.
- **Мониторинг.** `metric_samples` — time-series (1 точка/мин, retention 7 дней): pod cgroup CPU/RAM, JVM heap/threads, Xray RSS/CPU/IO, EU-node статус. Графики — inline SVG.

---

## ✅ Что сделано

**Инфраструктура:** Monorepo Gradle, Java 25, SB 4.0.8, GraalVM Native (~98 МБ бинарь, старт 60 мс), Docker multi-stage, профили dev/prod, deploy через `install.sh`.

**БД:** SQLite + Liquibase (SQL-миграции 001–009), `ddl-auto: validate`, `InstantStringConverter`, `JdbcTypeCode` для UUID/boolean/INTEGER, WAL + busy_timeout.

**Модели:** `User`, `Subscription`, `XrayConfig`, `EuNode`, `EnrollmentToken`, `InviteToken`, `ClientConfig`, `MetricSample`, `TelegramProxyConfig`, `TelegramProxyUser`.

**UI:** Login, `/home` (панели импорта + advanced block + TG-ссылка), `/admin` (grid карточек), CRUD users/subscriptions/invites/nodes/configs/scripts/client-configs/telegram, `/admin/xray` (live-логи через WS + фильтр по клиенту), `/admin/monitoring`, `/admin/users/{id}/tg`. Breadcrumbs. RU/EN i18n через cookie. Адаптив под мобильные.

**Xray:** Installer с fallback, `XrayProcessManager` (PID-файл + stale-recovery), `XrayRealityService` (x25519 в обоих форматах), `XrayConfigService` (build/parse vless-ссылок), `XrayConfigMaterializer` (inject клиентов + EU-outbound + healthcheck-socks + telemt-socks + routing), `XrayAssetService` (geosite/geoip).

**Подписки:** `GET /sub/{token}` — Base64 vless-ссылок, headers, URL через `PublicUrlResolver`. `/sub/{token}/rules.json`. Per-user UUID. Автосоздание при первом `/home`. Материализация через `SubscriptionsChangedListener` (AFTER_COMMIT).

**EU-ноды:** Enrollment API (`/api/nodes/bootstrap.sh`, `/register`, `/{id}/config`, `/{id}/health`, `agent.sh`), admin UI, pohr-agent.sh (polling + sync версии + health). `TunnelHealthCheckService` через SOCKS :10808.

**Telegram MTProto (telemt):**
- `TelemtInstaller` — скачивание `telemt-{platform}.tar.gz` (Linux only), распаковка через `tar -xzf`, `.bak` предыдущего бинарника.
- `TelemtVersionRegistry`/`TelemtVersionResolver` — `TELEMT_VERSION` файл, GitHub API `/releases/latest`.
- `TelemtProcessManager` — запуск/остановка/рестарт, лог-ридер в stdout.
- `TelegramProxyService` — глобальный конфиг (порт, cover domain, WEB), `apply()` (генерация секретов + `telemt.toml` + материализация Xray + рестарт telemt), per-user toggle/regenerate.
- `TelegramProxyUserInitializer` — автосоздание TG-записи при `UserCreatedEvent`, синхронизация label при `UserRenamedEvent`.
- Схема: **в БД и в `telemt.toml`** — 32-hex секрет; **в клиентской ссылке** — `ee` + 32-hex + hex(coverDomain). Ключи routing: `telemt-socks` inbound → `eu` outbound.
- UI: `/admin/telegram` (статус, Start/Stop/Restart/Install, глобальный конфиг, read-only список юзеров), `/admin/users/{id}/tg` (ссылка + toggle/regenerate), ссылка на `/home`.
- Nginx stream: SNI `<cover-domain>` → `telemt_backend` (127.0.0.1:3128).

**HTTPS:** Caddy с LE-сертификатами, `forward-headers-strategy: framework`, Nginx stream для SNI-мультиплексирования.

**Инвайты:** Полный цикл — создание токена, страница enroll, `/invite/{token}`, автологин через `HttpSessionSecurityContextRepository`.

**Клиентские заголовки:** Редактор в `/admin/client-configs`, longest match wins. Seed-дефолты для happ/v2rayng/v2rayn.

**Мониторинг:** `MetricsCollector` (cgroup v1/v2 + /proc + JVM), `MetricsService.toSvgPath`, `ContainerMetrics` детектит container vs host.

**Хостинг:** RU — Selectel, Debian 11 (EOL), 45.131.43.6. EU — 78.17.145.160, Debian 11 (EOL), SSH :6155. Домен `pohr-roclh.xyz` (Reg.ru).

---

## 🚧 Что предстоит

### P0 — стабильность и скрытность
- **Rebuild RU/EU на Debian 12/13.** EOL-репы = грабли.
- **`logrotate` для Xray-логов на EU** (`daily`, `rotate 7`, `maxsize 100M`, `copytruncate`).
- **Docker daemon.json** — `max-size: 10m`, `max-file: 3`.
- **Проверить, что Let's Encrypt не палит домен** — `crt.sh` показывает `pohr-roclh.xyz → 45.131.43.6`.
- **Nginx stream**: закрыть 3128 от интернета (`127.0.0.1:PORT:PORT` в compose).

### P1 — надёжность клиентских установщиков
- Детект версии v2rayN (CoreBasicItem → v7).
- Проверка запуска v2rayN после правки, restore из pristine при падении.
- `-Fragment on|off|keep`, `-Diagnose`.
- Идемпотентный рестарт.
- Нормализация line endings в `ClientScriptService.write()`.

### P2 — клиентские установщики (расширение)
- DNS в v2rayN, TUN-режим, системный прокси.
- Routing rules из БД.
- «Готовый ZIP» с преднастроенным v2rayN.

### P3 — мобильные клиенты
- Deep-link: Happ, v2rayNG, Streisand, Karing, Shadowrocket.
- QR-код на `/home`.
- Happ: Provider ID.

### P4 — масштабирование
- gRPC API Xray (`AlterInbound`) — убирает рестарт при новом юзере.
- Множественные EU-узлы с приоритетами.
- Fallback на freedom при UNREACHABLE EU.
- Health-check endpoint `/api/health` с деталями.

### P5 — UX
- Telegram-бот (`/start`, `/config`, `/qr`, `/status`).
- Смена пароля через UI.
- Страница `/status` для юзера.

### P6 — безопасность
- Лимиты трафика/скорости (Xray stats API или nftables).
- Аудит действий, rate limiting на `/sub/{token}`.
- Ротация `nodeSecret` раз в 30 дней.
- Сузить публичный `/api/health`.

### P7 — высший пилотаж
- GitHub-коммит скриптов из UI.
- Синхронизация скриптов между инстансами.
- **Telemt: рескрейп Prometheus-метрик** (`/metrics` на 9400) в `MetricsCollector`.

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
| Telegram MTProto | telemt (Rust), SOCKS5 upstream → Xray |
| Lombok | 1.18.42 |
| Jackson | tools.jackson (Jackson 3) |

---

## 📐 Правила разработки

### Оформление

1. **DTO — record** в `org.Roclh.model.dto` (подпакеты по домену: `.telegram`). Имя `XxxDto`, `XxxForm`.
2. **Сущности** — в `org.Roclh.model`, Lombok `@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor`.
3. **Репозитории** — `org.Roclh.repository` (+ подпакеты), `JpaRepository<T, ID>`.
4. **Сервисы** — `service`, `service.xray`, `service.node`, `service.metric`, `service.telegram`.
5. **Контроллеры:** `controller`, `controller.admin`, `controller.api`.
6. **Конфигурации** — `config`, `config.xray`, `config.ws`.
7. **Исключения** — `IllegalArgumentException` где хватает.

### Соглашения

- **Все id сущностей — UUID (`VARCHAR(36)`)**, кроме **time-series** (`MetricSample`): `Long` + `INTEGER PRIMARY KEY AUTOINCREMENT`. Требует `@Column(columnDefinition = "INTEGER")`.
- **`Instant`** — ISO-8601 строки через `InstantStringConverter`.
- **boolean** — `INTEGER` + `@JdbcTypeCode(SqlTypes.INTEGER)`.
- **UUID** — `VARCHAR(36)` + `@JdbcTypeCode(SqlTypes.VARCHAR)`.
- **`ddl-auto: validate`**, только Liquibase changesets, не редактировать применённые.
- **Liquibase** — SQL-миграции, `--liquibase formatted sql`.
- **Xray-конфиг** — JSON, `ObjectMapper`/`JsonNode` из `tools.jackson.databind`.
- **Логи** — `@Slf4j`.
- **i18n** — `messages.properties` + `messages_ru.properties`.
- **Нормализация line endings** при записи скриптов: `.sh` → LF, `.bat/.cmd/.ps1` → CRLF.
- **Thymeleaf fragments не вкладывать друг в друга**.
- **`<meta name="viewport">`** обязателен на каждой странице с собственным `<head>`.
- **Не использовать `#httpServletRequest`/`#request`** в шаблонах — передавать из контроллера через `Model`.
- **Не использовать `datetime('now')` в SQL-миграциях** для `Instant`-колонок — только `strftime('%Y-%m-%dT%H:%M:%fZ', 'now')`.
- **В Thymeleaf path-переменные должны совпадать по имени**: `@{/admin/users/{id}/tg(id=${user.id})}` — не `(f=...)`, не `(userId=...)`.

### Профили

- **dev** — `xray.home: ./xray`, Thymeleaf `cache: false`.
- **prod** — `xray.home: /app/xray`, `XRAY_AUTO_INSTALL=false`, `SPRING_PROFILES_ACTIVE=prod`.

### Работа с Xray

- **Источник истины — БД.** Правки в файл теряются при следующем restart.
- **Не перезапускать без нужды.** Для добавления юзеров — событие + restart (gRPC в плане).
- **Один владелец процесса** — stale убивается по PID-файлу.
- **Логи через WS** — `XrayLogWebSocketHandler.broadcast` из `XrayProcessManager.appendLog`.
- **Fallback URL** через HEAD, primary → fallback.

### Работа с telemt

- **Источник истины — БД.** `telemt.toml` — материализация, перезаписывается при каждом `apply()`.
- **Секрет в конфиге — 32 hex.** Не `ee`, не base64. Генерируется через `SecureRandom` (16 байт) → `HexFormat.formatHex`.
- **Секрет в клиентской ссылке — `ee` + 32-hex + hex(coverDomain).** Собирается в `TelegramProxyService.toUserDto`, не хранится.
- **Cover domain в трёх местах должен совпадать**: поле в UI, `tls_domain` в `telemt.toml`, hex-суффикс в ссылке. При смене — Regenerate у всех.
- **SNI-mux обязателен.** telemt слушает :3128, наружу — только Nginx stream на :443.
- **`use_middle_proxy = false`.** Иначе telemt пойдёт в Telegram ME-серверы напрямую, минуя SOCKS.
- **Управление процессом**: только через `TelemtProcessManager`, PID не пишется в файл (в отличие от Xray).

### Профиль разработчика

- Локально — `./gradlew bootRun --args='--spring.profiles.active=dev'`.
- Не коммитить: `build/`, `.gradle/`, `data/`, `xray/bin/`, `xray/config/config.json`, `xray/xray.pid`, `*.db`, `telemt.toml`.
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
8. Hibernate 7 строго валидирует типы. Для `Long` id в SQLite требуется `columnDefinition = "INTEGER"`.
9. `@Modifying`-запрос требует `@Transactional` на вызывающем методе.
10. UUID-колонка должна быть `unique`. Использовать `randomblob` для бэкфилла.
11. **`datetime('now')` в миграции → `2026-10-04 12:54:15`, `Instant.parse` падает на индексе 10.** Только `strftime('%Y-%m-%dT%H:%M:%fZ', 'now')`.
12. **`SQLITE_BUSY` при параллельных записях.** Только `journal_mode=WAL` + `busy_timeout=5000` + `hikari.maximum-pool-size=4`.

**Windows / Native**
13. `ProcessBuilder("unzip")` на Windows не работает — `java.util.zip.ZipInputStream`.
14. `Path.normalize()` без `toAbsolutePath()` ломает `startsWith` (Zip Slip).
15. `Path.of("/app/xray")` на Windows → `\app\xray`.
16. Native image не включает нестандартные Charset'ы — `-H:+AddAllCharsets`.
17. `ARG` не наследуется между стадиями Dockerfile.
18. Gradle в Docker — BuildKit cache mounts вместо проброса `~/.gradle`.
19. `COPY --from=builder` с путём, содержащим пробелы, ломается.

**Native image + Thymeleaf**
20. `Stream.toList()` возвращает immutable — SpEL reflection падает. Оборачивать в `new ArrayList<>()`.
21. `Instant.toString()` требует reflection — в DTO передавать строки.
22. Все 13 `org.thymeleaf.expression.*` требуют `MemberCategory.INVOKE_PUBLIC_METHODS`.
23. Любой DTO, рендерящийся в шаблоне, требует `MemberCategory.values()` в NativeHints.
24. `${obj.someString().toLowerCase()}` — тоже reflection. Использовать `#strings.toLowerCase()`.
25. **`#httpServletRequest` недоступен** в Thymeleaf 3.1 даже в JVM. Передавать флаг из контроллера через `@ModelAttribute`.
26. **Фрагменты не вкладывать друг в друга** — параметры не пробрасываются, `#{null}` → `Message key cannot be null`.
27. **`<meta name="viewport">`** обязателен на каждой странице с собственным `<head>`.
28. **Path-переменные в URL должны совпадать по имени с параметром.** `@{/x/{id}/y(id=...)}` — да, `@{/x/{id}/y(f=...)}` — нет.
29. `<th:block>` вместо `<ng-container>` — не оставляет HTML-тег.

**Xray / Reality**
30. x25519 формат менялся: `Private key:` ↔ `PrivateKey:`, `Public key:` ↔ `Password (PublicKey):`. Парсер — оба, `Hash32:` игнорировать.
31. xhttp + Reality несовместим с `flow=xtls-rprx-vision`. Flow только при `network=tcp`.
32. `realitySettings.serverNames` может быть массивом — в ссылку идёт `[0]`.
33. `fingerprint`/`spiderX`/`alpn` — читать из JSON, не хардкодить.
34. Xray требует `geosite.dat`/`geoip.dat` для правил `geosite:*`/`geoip:*`.
35. `xray version | head -1` под `set -o pipefail` падает с SIGPIPE.
36. Reality выбирает SNI — не-443 порт палит.
37. **`sniffing` в RU-inbound на xhttp ломает handshake.** Убрали. Не возвращать без `routeOnly: true`.
38. **Версии Xray должны совпадать** на клиенте, RU и EU.
39. `__USE_LOCAL__` в EU-конфиге — уникальная строка, `sed` только в `privateKey`.

**EU-ноды / скрипты**
40. `curl | sudo bash` должен быть идемпотентным.
41. `iptables -F` — СТОП. Точечное удаление правил на порт Xray.
42. `jq` может отсутствовать — ставить в bootstrap.
43. EU за NAT — `api.ipify.org` вернёт публичный IP, но входящие могут не доходить.
44. `systemctl restart xray` рвёт соединения.
45. Порт SSH может быть нестандартным (`ss -tlnp | grep sshd`).
46. `nft` вместо `iptables` на Debian 12+.
47. Синхронизация версии EU→RU только через `X-Pohr-Xray-Version` header.

**Клиентские скрипты / v2rayN**
48. v2rayN: тип конвертации подписки — «v2ray».
49. v2rayN: URL с `localhost` не работает. Использовать `127.0.0.1`.
50. v2rayN 7.x переехал `guiNConfig.json` в `guiConfigs/`.
51. v2rayN 7.x формат фрагмента: `CoreBasicItem.EnableFragment` + `Fragment4RayItem.{Packets,Lengths[],Delays[],MaxSplit}`.
52. `chcp 65001` нестабилен — `.bat` в Cp866, `.ps1` в UTF-8+BOM.
53. PS 5.1 без BOM читает `.ps1` как cp1251.
54. `Set-Content -Encoding UTF8` в PS 5.1 добавляет BOM.
55. `return` из `ForEach-Object` не прерывает цикл — использовать флаг.
56. `Get-Process v2rayN` даёт exe, но не путь к конфигу.
57. Pristine-бэкап создавать один раз, до первой модификации.

**Docker / Deploy**
58. `get.docker.com` EOL-проверка ломается на Debian 11.
59. Docker на 20 ГБ VPS забивает диск — `/etc/docker/daemon.json`.
60. `/var/log/xray` без ротации — гигабайты. logrotate обязателен.
61. systemd-journal тоже без лимита — `SystemMaxUse=200M`.
62. Debian 11 EOL — `security.debian.org` 404, использовать `archive.debian.org`.
63. **Loopback bind для внутренних портов**: `127.0.0.1:${PORT}:${PORT}` в compose.

**Подписки / URL**
64. `WebController.home` собирает URL из `request.getScheme()` — за Caddy будет `http://host:80/...`. Использовать `PublicUrlResolver`.
65. `XrayConfigService.parseLinks` фильтрует по UUID подписки.
66. Let's Encrypt логирует всё в `crt.sh`.
67. `server.forward-headers-strategy: framework` обязателен за прокси.
68. URL подписки на Windows с `localhost` не работает.
69. Для v2rayN deep-link — `v2rayng://install-sub?url=BASE64&name=Pohr` (не URL-encoded).

**Happ**
70. Расширенные параметры (fragmentation, routing) требуют **Provider ID** от happ-proxy.com.
71. Happ 5.9.0 имеет баг с `noise`.
72. Happ на iOS + xhttp + Reality — fragmentation часто не применяется.
73. `fragmentation-enable` и др. передаются через HTTP-заголовки или тело `#key: value`.

**Telegram / telemt**
74. **Формат секрета — три разных представления одного значения:**
    - В БД (`telegram_proxy_users.secret`) и в `[access.users]` — **32 hex**.
    - В клиентской ссылке `tg://proxy?...&secret=...` — **`ee` + 32hex + hex(coverDomain)**.
    - В конфиге telemt секрет **не должен** содержать `ee`.
      Ошибка `Invalid secret for user admin: Must be 32 hex characters` — прямое следствие путаницы.
75. **URL релизов telemt без `v` в версии:** `https://github.com/telemt/telemt/releases/download/{version}/telemt-{platform}.tar.gz`, не `v{version}`.
76. **telemt собран только под Linux.** На Windows/macOS `TelemtPlatform.current()` кидает `UnsupportedOperationException`. Работает только в Docker/на проде.
77. **telemt тарболл**, не голый бинарник. Распаковка через `tar -xzf`, поиск `telemt` в extractDir через `Files.walk`.
78. **`--gen-secret` не существует.** Секрет = 16 случайных байт в Java, не через CLI.
79. **`telemt --help`**: команды `run|start|stop|reload|status`, `--init` для setup, `--data-path`, `--log-level`. Конфиг передаётся **позиционно** (`telemt run config.toml`), не через `--config`.
80. **`use_middle_proxy = false`** обязателен, если upstream — SOCKS5. Иначе telemt идёт в ME Telegram напрямую.
81. **SOCKS5-апстрим через `[[upstreams]]`:** `type = "socks5"`, `address = "127.0.0.1:10809"`, `enabled = true`, `weight = 1`.
82. **Cover domain должен быть в РФ-сегменте и не за Cloudflare.** Cloudflare-домены не пропускают MTProto, и DPI спалит несоответствие SNI↔AS. Хорошие: `dodopizza.ru` (AS201706, Selectel-совместимо), `www.microsoft.com` — плохие для RU-IP.
83. **Проверка cover domain** — `openssl s_client -connect <domain>:443 -servername <domain>` + `nslookup`. SAN должен включать домен; issuer — публичный CA (Google Trust Services, DigiCert, LE); стабильность — 5 запусков дают один fingerprint.
84. **Nginx stream `map`:** SNI `<cover-domain>` → `telemt_backend { server 127.0.0.1:3128; }`. После изменений `nginx -t && systemctl reload nginx`.
85. **Проверка SNI-mux:** `openssl s_client -connect <host>:443 -servername <cover-domain>` должен вернуть сертификат cover domain, не LE-сертификат Pohr.
86. **Порт 3128 закрыт от интернета.** Только Nginx на хосте видит его через loopback.
87. **`bootstrap()` убран из `TelegramProxyService`.** Telemt не поднимается автоматически при старте приложения, только через UI. Причина: конфиг требует уже сгенерированных секретов, которых нет при первом запуске.
88. **Порядок в `apply()`:** `ensureInstalled` → генерация отсутствующих секретов → `writeTelemtConfig` → `materializer.materialize()` + рестарт Xray → запуск/рестарт telemt. Нарушение порядка = telemt читает старый TOML без свежих секретов.
89. **Cover domain меняется в трёх местах** — UI, `telemt.toml` (генерируется из UI), hex-суффикс в ссылке. При смене — Regenerate у каждого пользователя, иначе hex-суффикс не совпадёт с `tls_domain`.

**UI / CSS**
90. `#httpServletRequest` недоступен — передавать флаги из контроллера.
91. `<meta name="viewport">` обязателен на каждой странице.
92. CRLF в `.sh` ломает bash.

**Сторонние бинарники (Xray, telemt, будущие).**
93. Единый паттерн:
    - `XxxVersionRegistry` — файл `<name>/<NAME>_VERSION` в `xray.home`.
    - `XxxVersionResolver` — GitHub API `/releases/latest`, тег без `v`.
    - `XxxInstaller` — `install(version)` с `download-url-template` / `fallback-url-template`, HEAD-проверка primary, `.bak` предыдущего бинарника.
    - `XxxPlatform` — enum с суффиксами по ОС/архитектуре.
    - В YAML — секция `install: { version, download-url-template, fallback-url-template }` в `application.yml` (dev) и `application-prod.yml` (prod, через env).
    - В UI — карточка «Установка / Обновление» с input версии и кнопкой Install.
    - `latest` — резолвится через `XxxVersionResolver`, иначе берётся как есть.
    - URL-шаблон должен быть **env-override-абельным** (`${XXX_DOWNLOAD_URL:default}`), чтобы 404/mirror лечились без пересборки.
    - **Перед интеграцией CLI снимать `--help` и дефолтный конфиг.** Не выдумывать флаги по аналогии.

---

## 📋 Открытые вопросы

1. Fallback на freedom при UNREACHABLE EU.
2. gRPC Xray — до или после мобильных клиентов?
3. Rebuild на Debian 12 — когда?
4. Routing rules из БД — делать или хардкод?
5. Обновление клиентских скриптов — как уведомлять?
6. Мультитенантность — один админ или несколько?
7. PostgreSQL — когда нужен?
8. Ротация subscription-токенов — ручная или авто?
9. `nodeSecret` ротация — 30 дней автоматически?
10. Backup приватного ключа EU.
11. Ротация Xray версий — guard semver в `update_xray()`.
12. `xfit.ru` как target Reality — правильно ли?
13. Материализация через gRPC — снимет требование restart Xray.
14. **Скрейп Prometheus-метрик telemt** (`/metrics` на :9400) в `MetricsCollector` — отдельные поля `tgConnections`, `tgBytesIn`, `tgBytesOut`.
15. **WEB-режим telemt** — TLS-терминация на Nginx/Haproxy на :443 для `tg://webproxy`. Требует отдельной настройки.

---

## 🎬 Как начать новый диалог

Привет. Продолжаем разработку **Pohr** — системы управления VPN-инфраструктурой для обхода блокировок РКН.

**Стек:** Java 25 + Spring Boot 4.0.8 + GraalVM Native + SQLite + Liquibase + Thymeleaf + Spring Security + Xray-core + telemt.

**Работает end-to-end:**
- VPN: клиент → RU-мост → EU-узел → интернет через Reality (xhttp).
- Telegram: клиент → Nginx stream :443 (SNI=cover domain) → telemt :3128 → Xray SOCKS :10809 → eu outbound → Telegram DC.

**Сделано:** Xray-конфиги в БД + материализация, per-user UUID, SNI-мультиплексирование через Nginx stream, инвайты, client configs, мониторинг (метрики + SVG-графики), адаптивный UI, breadcrumbs, admin grid, deep-link кнопки, **Telegram MTProto через telemt с per-user секретами, маршрутизацией в EU-туннель, cover domain маскировкой через Nginx SNI-mux**.

**Правила:** DTO — record в `model/dto`, сущности в `model`, Liquibase SQL, `ddl-auto: validate`, id — UUID (кроме time-series — `Long INTEGER`), время — `Instant` через `InstantStringConverter`, **Xray-конфиг в БД, файл — материализация**, **telemt.toml — материализация**, **клиентские скрипты в volume**, **EU-ноды — pull-модель**, **sniffing убран из RU-inbound**, **`#httpServletRequest` недоступен** (передавать флаги из контроллера), **`<meta viewport>` обязателен на каждой странице**, **path-переменные Thymeleaf должны совпадать по имени с параметром**, **`datetime('now')` запрещён в Liquibase**, **telegram-секрет: 32 hex в БД/TOML, `ee`+32hex+hex(domain) в ссылке**.

**Предстоит:** rebuild на Debian 12, logrotate, ограничить Docker-логи, мобильные клиенты, gRPC Xray, множественные EU с fallback, TG-бот, routing rules в БД, скрейп Prometheus telemt.

**Следующий шаг:** [что делаем].

---

## 🔑 Итоговый принцип

**Pohr — это не «ещё один VPN-сервер». Это платформа для администратора, который хочет:**
- тратить 5 минут на развёртывание, а не 5 часов;
- не думать о блокировках — система сама переключает узлы;
- выдавать подписки и TG-прокси одним кликом;
- редактировать Xray-конфиг в удобном редакторе;
- прятаться от DPI через Reality и cover domain, от провайдера VPS — через маскировку процессов и трафика.

**Всё подчинено этим целям. Любая новая фича проверяется вопросом: «Это упрощает жизнь админа или усложняет? Работает ли это в условиях блокировок? Не палит ли это систему?»**