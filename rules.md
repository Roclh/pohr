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
| **SQLite** | Пользователи, подписки, метаданные EU-узлов |

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
- [x] Liquibase, SQL-миграции в `db/changelog/changes/001-init/`, `002-subscriptions/`
- [x] `ddl-auto: validate` (Liquibase управляет схемой, Hibernate только проверяет)
- [x] `InstantStringConverter` — `Instant` ↔ ISO-8601 строка с `Z` (SQLite не имеет TIMESTAMPTZ)
- [x] `JdbcTypeCode(SqlTypes.VARCHAR)` для UUID, `JdbcTypeCode(SqlTypes.INTEGER)` для boolean

### Модели и сервисы

- [x] `User` (UUID, username, passwordHash, role, enabled, createdAt)
- [x] `Subscription` (UUID, userId, token, enabled, createdAt)
- [x] `UserRepository`, `SubscriptionRepository`
- [x] `UserService`, `SubscriptionService`
- [x] `DatabaseUserDetailsService` — интеграция с Spring Security
- [x] `AdminInitializer` — создаёт `admin / changeme` при первом старте

### UI (Spring MVC + Thymeleaf)

- [x] Страницы: `/login`, `/home`, `/admin`
- [x] `/admin/users` — CRUD пользователей
- [x] `/admin/subscriptions` — список подписок, копирование URL, toggle, delete
- [x] `/admin/xray` — статус процесса, install/update, кнопки Start/Stop/Restart, live-логи через WebSocket
- [x] `LocaleConfig` — переключение RU/EN через cookie, i18n через `.properties`
- [x] Bootstrap-free CSS, светлая тема

### Xray

- [x] `XrayInstaller` — скачивание и установка с GitHub или fallback-зеркала
- [x] `XrayPlatform` — автоопределение ОС/арх (linux/windows/macos × amd64/arm64)
- [x] `XrayVersionResolver` — получение последней версии через GitHub API
- [x] `XrayProcessManager` — ProcessBuilder, PID, логи в буфер, синхронизация
- [x] `XrayConfigService` — парсит `config.json`, генерирует `vless://` ссылки
- [x] `XrayVersionRegistry` — хранит установленную версию в файле
- [x] `XrayAutoInstaller` — устанавливает при старте, если `auto: true`
- [x] Дефолтный конфиг генерируется при первой установке
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

- [x] Эндпоинт `GET /sub/{token}` — отдаёт Base64-список `vless://` ссылок
- [x] Заголовок `Profile-Update-Interval: 6` (часов)
- [x] URL подписки собирается динамически с учётом host/port запроса
- [x] Подписка создаётся автоматически при первом заходе пользователя на `/home`

### Native Image и GraalVM

- [x] `NativeHints` — регистрация `org.sqlite.JDBC`, `SQLiteDialect`, Liquibase-парсеров
- [x] `hints.resources().registerPattern("db/changelog/**")`, `i18n/messages*`
- [x] Работает установка Xray из нативного образа (Java HTTP Client, ZipInputStream)

---

## 🚧 Что предстоит сделать

### Приоритет 1: рабочий VPN

- [ ] **Reality вместо чистого VLESS.** Генерация `x25519` ключей, `shortIds`, настройка `dest`, `serverNames`. Обновить `XrayConfigService` для `vless://...?security=reality&pbk=...&sni=...&sid=...&fp=chrome&flow=xtls-rprx-vision`.
- [ ] **Генерация UUID для каждого пользователя.** Сейчас в конфиге захардкожен один UUID. Нужно: при создании подписки генерировать UUID, добавлять в `config.json` (или через gRPC API), в ссылку подставлять свой UUID.
- [ ] **Редактор `config.json` в UI.** Monaco Editor через CDN, AJAX-сохранение, валидация JSON перед записью, backup старого файла.

### Приоритет 2: масштабирование

- [ ] **gRPC API Xray.** Вместо перезапуска процесса — `HandlerService.AlterInbound` с `AddUserOperation` / `RemoveUserOperation`. Мгновенное добавление пользователей без обрыва соединений.
- [ ] **Множественные EU-узлы.** Хранение списка в БД, health-check, автоматическое переключение при блокировке.
- [ ] **Health-check endpoint'ов.** `/api/health` расширить: статус EU-узлов, состояние Xray.

### Приоритет 3: развёртывание

- [ ] **`install.sh` для RU-моста.** Скачивает Docker-образ, генерирует секреты, запускает контейнер.
- [ ] **Одна curl-команда для EU-узла.** `curl -sL https://pohr.example/install-eu | bash` — устанавливает Xray, регистрируется на RU-мосте.
- [ ] **Self-steal маскировка.** Nginx с сайтом-заглушкой на `127.0.0.1:8443`, Xray `dest` туда же.
- [ ] **Зеркало GitHub.** Кэширование релизов Xray на RU-мосте для обхода блокировок.

### Приоритет 4: UX и удобство

- [ ] **Telegram-бот.** Команды `/start`, `/config`, `/qr`, `/status`. Интеграция с существующим API.
- [ ] **QR-код для мобильных.** Генерация `vless://` в QR для iOS/Android.
- [ ] **Готовый ZIP с преднастроенным v2rayN** для Windows-клиентов.
- [ ] **Смена пароля через UI.** Пользователь меняет свой пароль на `/home`.
- [ ] **Диагностика.** Страница `/status` для пользователя: активен ли EU-узел, когда обновлялась подписка.

### Приоритет 5: безопасность и приватность

- [ ] **Self-registration с invite-токенами.** Админ генерирует токены, пользователь регистрируется сам.
- [ ] **Лимиты трафика и скорости.** На пользователя, на подписку.
- [ ] **Аудит действий.** Лог: кто, когда, что менял в конфиге.
- [ ] **Rate limiting** на `/sub/{token}` от перебора.

---

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

1. **DTO — только record** в пакете `org.Roclh.model.dto`. Имя: `XxxDto` (для отдачи в UI/API), `XxxForm` (для приёма форм).
2. **Сущности** — в `org.Roclh.model`, Lombok `@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor`.
3. **Репозитории** — интерфейсы в `org.Roclh.repository`, наследуют `JpaRepository<T, UUID>`.
4. **Сервисы** — в `org.Roclh.service` (для доменных: `service`; для Xray — `service.xray`).
5. **Контроллеры:**
    - `org.Roclh.controller` — пользовательские (`/home`, `/login`, `/sub`).
    - `org.Roclh.controller.admin` — админские (`/admin/**`).
6. **Конфигурации** — в `org.Roclh.config` (для доменных), `org.Roclh.config.xray` (для Xray), `org.Roclh.config.ws` (для WebSocket).
7. **Исключения** — через `throw new IllegalArgumentException(...)` там, где не нужен кастомный класс. Для сложных случаев — `XxxException` в `org.Roclh.exception`.

### Соглашения

- **Все id сущностей — UUID**, хранятся как `VARCHAR(36)` в SQLite.
- **Все `Instant`** хранятся как ISO-8601 строки через `InstantStringConverter` (`autoApply = true`).
- **Все boolean** в SQLite — `INTEGER`, обязательно `@JdbcTypeCode(SqlTypes.INTEGER)`.
- **Все UUID** в SQLite — `VARCHAR(36)`, обязательно `@JdbcTypeCode(SqlTypes.VARCHAR)`.
- **Не использовать `ddl-auto: update`**. Только `validate`. Схема — через Liquibase changesets.
- **Не редактировать применённые changesets.** Только новые.
- **Liquibase: SQL-миграции**, а не YAML/XML. Формат `--liquibase formatted sql`.
- **Правила Xray-конфига** — JSON, `ObjectMapper` из `tools.jackson.databind`, `JsonNode` из `tools.jackson.databind`.
- **Логи** — `@Slf4j` (Lombok) + `log.info/warn/error/debug`. Никаких `System.out`.
- **i18n** — все сообщения в `messages.properties` (EN) и `messages_ru.properties` (RU). Ключи: `<раздел>.<сущность>.<поле>`, например `user.form.username`, `admin.xray.start`.

### Профили

- **`dev`** — `xray.home: ./xray`, `xray.install.auto: true`, Thymeleaf `cache: false`.
- **`prod`** — `xray.home: /app/xray`, `xray.install.auto: true`, `SPRING_PROFILES_ACTIVE=prod`.
- **Без профиля** — только базовая конфигурация (datasource, jpa, liquibase, server.port).

### Работа с Xray

- **Не перезапускать без нужды.** При добавлении пользователей — либо перезапуск (пока), либо gRPC (план).
- **Логи Xray — в WebSocket.** `XrayLogWebSocketHandler.broadcast(line)` вызывается из `XrayProcessManager.appendLog`. Обработчик ловит `Exception` (не только `IOException`), синхронизируется на сессии, удаляет мёртвые из набора.
- **Fallback URL для скачивания.** `xray.install.download-url-template` (GitHub) + `xray.install.fallback-url-template` (своё зеркало). Проверка через HEAD-запрос.
- **Версия по умолчанию — `latest`.** `XrayVersionResolver.resolveLatest()` дёргает GitHub API.

### Профиль разработчика

- **Локально — `./gradlew bootRun --args='--spring.profiles.active=dev'`**, а не `nativeCompile`. Native — только перед деплоем.
- **Xray в dev-режиме запускается через приложение.** `auto: true`, скачивается под Windows-бинарник.
- **Никогда не коммитить** `build/`, `.gradle/`, `data/`, `xray/bin/`, `xray/config/config.json`, `*.db`.

### Правила для новых фич

- **Проверять работу в native image** после каждой крупной фичи. Если упало — добавить hints в `NativeHints`, не откладывать.
- **Тестировать на чистой БД.** Удалить `data/pohr.db` перед проверкой миграций.
- **Не добавлять зависимостей без нужды.** Каждая зависимость = +X МБ к native + потенциальные hints.

---

## ⚠️ Известные грабли (не наступить снова)

1. **Lombok < 1.18.42 не работает с JDK 25.** Обязательно 1.18.42+.
2. **Windows: Gradle ищет `native-image.exe`, но GraalVM кладёт `native-image.cmd`.** Решение: `nativeImageCapable = false` в toolchain + явный путь в `org.gradle.java.installations.paths`.
3. **`.properties` файлы читаются в ISO-8859-1.** Русский текст — только через Unicode-escape или чтобы IDE сохраняла как ISO-8859-1. Проще — писать английский, а русский в `messages_ru.properties` через IDE с правильной кодировкой.
4. **`spring.factories` для `EnvironmentPostProcessor` в Spring Boot 4 не работает** (переехало в `META-INF/spring/...imports`). Проще — делать инициализацию в `main()` до `SpringApplication.run`.
5. **`Path.of("/app/xray")` на Windows** превращается в `\app\xray` — корень диска C:. Профильная конфигурация с `./xray` в dev решает проблему.
6. **`@PostConstruct` может сработать позже `DataSource`** — папку `data/` создавать в `main()`.
7. **SQLite не создаёт родительские директории.** Нужно `Files.createDirectories` до первого подключения.
8. **SQLite не имеет `TIMESTAMP WITH TIME ZONE`.** Использовать ISO-8601 строки + `InstantStringConverter`.
9. **SQLite не имеет `UUID`.** Хранить как `VARCHAR(36)`, `@JdbcTypeCode(SqlTypes.VARCHAR)`.
10. **SQLite не имеет `BOOLEAN`.** `INTEGER` + `@JdbcTypeCode(SqlTypes.INTEGER)`.
11. **Hibernate 7 строго валидирует типы.** `ddl-auto: validate` упадёт на любом несоответствии.
12. **`ProcessBuilder("unzip", ...)` на Windows не работает.** Распаковывать через `java.util.zip.ZipInputStream`.
13. **`Path.normalize()` без `toAbsolutePath()`** ломает `startsWith` (баг Zip Slip).
14. **WebSocket `session.sendMessage()` может кинуть `IllegalStateException`** между `isOpen()` и отправкой. Ловить `Exception`, а не `IOException`.
15. **v2rayN: тип конвертации подписки.** В настройках группы подписки обязательно «Целевой тип конвертации: v2ray». Иначе `Arg_TimeoutException`.
16. **v2rayN: URL подписки с `localhost` не работает на Windows** (IPv6 vs IPv4). Использовать `127.0.0.1`.
17. **VLESS без TLS/Reality DPI режет мгновенно.** Для реального использования — только Reality.
18. **`th:disabled="${running}"`** работает как HTML-атрибут: если `true`, кнопка отключена.
19. **`th:text="${successMessage}"` для i18n ключей** нужно оборачивать в `#{...}`: `th:text="#{${successMessage}}"`.
20. **`nativeCompile` на 20-ядерной машине может сожрать 10 ГБ RAM.** На слабой машине — `-J-Xmx4g` и `--parallelism=2`.

---

## 📋 Открытые вопросы

1. **Как именно хранить пользователей в Xray-конфиге.** Сейчас — в `config.json`. Планируется: «чистый» конфиг в UI + генерация полного на лету из БД + пользователи. В идеале — gRPC API.
2. **Синхронизация `xray.server.host`.** Для dev — `127.0.0.1`, для prod — публичный IP. Где хранить — в `application-prod.yml` или в БД (админ меняет через UI).
3. **Ротация subscription-токенов.** При утечке — админ удаляет подписку, создаётся новая. Или автоматическая ротация раз в N дней.
4. **Мультитенантность.** Один админ или несколько? Пока — один (`admin`).
5. **PostgreSQL как альтернатива SQLite.** Для больших нагрузок. Сейчас не нужно, но архитектурно закладывать `ddl-auto: validate` + Liquibase — правильный путь.
6. **Стратегия сокрытия.** Простая (self-steal на том же VPS) или радикальная (SSH-инверсия RU → EU)?

---

## 🎬 Как начать новый диалог

Привет. Продолжаем разработку **Pohr** — системы управления VPN-инфраструктурой для обхода блокировок РКН.

Текущий статус (см. полный контекст ниже):
- [перечислить, что сделано и что предстоит]

Стек: Java 25 + Spring Boot 4.0.8 + GraalVM Native + SQLite + Liquibase + Thymeleaf + Spring Security + Xray-core.

Правила: DTO — record в `model/dto`, сущности в `model`, Liquibase через SQL-миграции, `ddl-auto: validate`, id — UUID, время — `Instant` через `InstantStringConverter`.

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