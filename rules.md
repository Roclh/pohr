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

---

## 🚧 Что предстоит сделать

### Приоритет 0: EU-ноды (автоматический enrollment + orchestration)

**Цель:** админ на RU-мосте создаёт ноду в UI → копирует одну `curl | bash` команду → запускает её на чистом EU-VPS → через 30 секунд EU-нода работает, туннель поднят, клиенты ходят через неё. Всё остальное — автоматически.

#### Архитектурные решения (зафиксированы)

- **Трафик:** клиент → RU Xray (inbound) → RU outbound VLESS → EU Xray (inbound) → freedom → интернет.
- **Auth:** клиент→RU по `xray_uuid` подписки; RU→EU по `tunnel_uuid` (один на EU-ноду). **EU ничего не знает про индивидуальных пользователей** — добавление юзера не трогает EU.
- **Ключи:** Reality keypair генерится **на EU локально** (`xray x25519`). На RU уходит только `publicKey`. RU **никогда** не видит `privateKey` EU. В шаблоне конфига EU для поля `privateKey` — плейсхолдер `"__USE_LOCAL__"`, EU подменяет его своим.
- **Enrollment:** одноразовый токен (TTL 1 час, генерится в UI), после enroll нода получает `nodeSecret` (64 hex, долгоживущий). Токен инвалидируется. Повторный enroll с тем же `name` → обновление существующей ноды (idempotent).
- **Обновления конфига:** EU **pull-модель**. Cron на EU каждые 5 минут дёргает `GET /api/nodes/{id}/config` с `If-None-Match: <hash>`. RU отвечает `304` если hash не изменился, иначе новый JSON. RU никогда не пушит EU.
- **Транспорт RU↔EU:** TCP + Reality (server-to-server, DPI неважен, оверхед минимальный). SNI/dest можно взять из глобальных `xray.reality.*` defaults.
- **Routing на RU:** `geoip:ru`, `geoip:private`, `geosite:category-ru` → direct (freedom); всё остальное → EU-outbound. Позволит сохранить российские сервисы на прямом канале.
- **Health:** два уровня. Пассивный — EU каждые 5 минут POST `/health` с `{xrayRunning, xrayVersion, configHash, uptime}`. Активный — RU по крону делает запрос через свой Xray-SOCKS на `https://api.ipify.org` и проверяет, что видит IP EU. Если IP RU — routing сломан; если ошибка — туннель недоступен.
- **SSH ключи:** RU хранит глобальный список (`pohr.ssh.authorized-keys`, multiline env или таблица). При enroll EU-скрипт добавляет их в `~/.ssh/authorized_keys` (root и/или созданный пользователь).

#### Шаг 1. Модель данных + сервисы

**Liquibase миграция** `db/changelog/005-eu-nodes/eu_nodes.sql`:

```sql
--liquibase formatted sql

--changeset roclh:005-eu-nodes
CREATE TABLE eu_nodes (
    id                   VARCHAR(36)  PRIMARY KEY NOT NULL,
    name                 VARCHAR(128) NOT NULL UNIQUE,
    host                 VARCHAR(255) NOT NULL,
    port                 INTEGER      NOT NULL,
    reality_public_key   VARCHAR(64)  NOT NULL,
    reality_short_id     VARCHAR(32)  NOT NULL,
    tunnel_uuid          VARCHAR(36)  NOT NULL,
    node_secret          VARCHAR(128) NOT NULL,
    status               VARCHAR(16)  NOT NULL,
    xray_version         VARCHAR(32),
    agent_version        VARCHAR(32),
    config_hash          VARCHAR(64),
    last_health_at       VARCHAR(32),
    last_health_msg      VARCHAR(512),
    last_tunnel_check_at VARCHAR(32),
    last_tunnel_ip       VARCHAR(64),
    enrolled_at          VARCHAR(32)  NOT NULL,
    enrolled_by          VARCHAR(36)  NOT NULL
);
CREATE UNIQUE INDEX idx_eu_nodes_tunnel_uuid ON eu_nodes (tunnel_uuid);
CREATE INDEX idx_eu_nodes_status ON eu_nodes (status);

--changeset roclh:005-enrollment-tokens
CREATE TABLE enrollment_tokens (
    token       VARCHAR(64) PRIMARY KEY NOT NULL,
    node_name   VARCHAR(128) NOT NULL,
    created_by  VARCHAR(36) NOT NULL,
    created_at  VARCHAR(32) NOT NULL,
    expires_at  VARCHAR(32) NOT NULL,
    used_at     VARCHAR(32)
);
CREATE INDEX idx_enrollment_tokens_expires ON enrollment_tokens (expires_at);
```

**Сущности:** `EuNode`, `EnrollmentToken`, `NodeStatus` (enum — хранится как VARCHAR: `PENDING`, `HEALTHY`, `DEGRADED`, `UNREACHABLE`).

`EuNode` поля и `@JdbcTypeCode`: id (UUID/VARCHAR), name, host, port (int), realityPublicKey, realityShortId, tunnelUuid (UUID/VARCHAR), nodeSecret, status (enum → VARCHAR), xrayVersion, agentVersion, configHash, lastHealthAt (Instant), lastHealthMsg, lastTunnelCheckAt (Instant), lastTunnelIp, enrolledAt (Instant), enrolledBy (UUID/VARCHAR).

**Репозитории:** `EuNodeRepository extends JpaRepository<EuNode, UUID>` с `findByName`, `findByStatusIn`, `existsByName`. `EnrollmentTokenRepository` с `findByExpiresAtBefore` (для очистки).

**`EuNodeService`:** CRUD, `createEnrollmentToken(name, ttl)`, `consumeToken(token)` (проверка TTL, one-time use), `register(EnrollmentRequest)` → создать/обновить ноду, вернуть `nodeSecret` + первый конфиг.

**Соглашения:**
- `nodeSecret` генерится как `SecureRandom` 32 байта → hex 64 символа.
- `tunnelUuid` — обычный `UUID.randomUUID()`.
- Хранить `nodeSecret` в plaintext? Для MVP — да (иначе EU не сможет проверить), но с оговоркой: если БД утечёт — компромисс. Позже — HMAC.

#### Шаг 2. Enrollment API + генерация конфигов

**`EuNodeApiController`** (`org.Roclh.controller.api`, `permitAll` в SecurityConfig **только для этих путей**):

| Endpoint | Auth | Что делает |
|---|---|---|
| `GET /api/nodes/bootstrap.sh?token=XXX` | no (токен) | Отдаёт `eu-node-setup.sh` с подставленными `{{ENROLL_TOKEN}}`, `{{RU_URL}}` (public URL моста), `{{SSH_KEYS}}` |
| `POST /api/nodes/register` | токен в body | Принимает `{token, host, port, publicKey, shortId, xrayVersion, agentVersion}`, возвращает `{nodeId, nodeSecret, config, configHash, ruPublicKey?}` |
| `GET /api/nodes/{id}/config` | header `X-Node-Secret` | Отдаёт актуальный конфиг EU. Поддерживает `If-None-Match` → 304 |
| `POST /api/nodes/{id}/health` | header `X-Node-Secret` | Принимает `{status, xrayVersion, configHash, uptime}`, обновляет `lastHealth*` |
| `POST /api/nodes/{id}/rotate-tunnel` | header `X-Node-Secret` | EU может запросить новый `tunnel_uuid` (например, если скомпрометирован) |

**`EuConfigService`** — генерирует JSON-конфиги на лету:

```java
// EU config (inbound + freedom)
public String buildEuConfig(EuNode node) {
    return """
      {
        "log": {"loglevel": "warning"},
        "inbounds": [{
          "listen": "0.0.0.0",
          "port": %d,
          "protocol": "vless",
          "settings": {
            "clients": [{"id": "%s", "email": "ru-bridge"}],
            "decryption": "none"
          },
          "streamSettings": {
            "network": "tcp",
            "security": "reality",
            "realitySettings": {
              "dest": "%s",
              "serverNames": ["%s"],
              "privateKey": "__USE_LOCAL__",
              "shortIds": ["%s"]
            }
          }
        }],
        "outbounds": [{"protocol": "freedom", "tag": "direct"}]
      }
    """.formatted(node.getPort(), node.getTunnelUuid(),
                  defaultDest, defaultSni, node.getRealityShortId());
}
```

**RU-конфиг** — материализуется в `XrayConfigMaterializer`:
- Из активного `XrayConfig` берём `inbounds`.
- Добавляем `outbound` к EU:
  ```json
  {
    "protocol": "vless",
    "tag": "eu",
    "settings": {
      "vnext": [{
        "address": "<eu.host>", "port": <eu.port>,
        "users": [{"id": "<eu.tunnel_uuid>", "encryption": "none", "flow": "xtls-rprx-vision"}]
      }]
    },
    "streamSettings": {
      "network": "tcp", "security": "reality",
      "realitySettings": {
        "serverName": "<sni>", "publicKey": "<eu.reality_public_key>",
        "shortId": "<eu.short_id>", "fingerprint": "chrome"
      }
    }
  }
  ```
- Добавляем `routing`:
  ```json
  {
    "domainStrategy": "IPIfNonMatch",
    "rules": [
      {"type": "field", "outboundTag": "direct", "domain": ["geosite:category-ru", "geosite:private"]},
      {"type": "field", "outboundTag": "direct", "ip": ["geoip:ru", "geoip:private"]},
      {"type": "field", "outboundTag": "eu", "network": "tcp,udp"}
    ]
  }
  ```

**Расширить `XrayConfigMaterializer`:**
- Найти активную `EuNode` (`status IN (HEALTHY, DEGRADED)`, сортировка по `enrolled_at DESC`, для MVP — первая).
- Если есть — добавить в материализуемый конфиг `eu` outbound + `routing` правила.
- Если нет — оставить конфиг без изменений (работает как сейчас, freedom).

**Считать `configHash`** как SHA-256 от **нормализованного** JSON (сериализация через `ObjectMapper` с сортировкой ключей) — чтобы любое изменение шаблона меняло hash.

**`SecurityConfig`** — добавить в permitAll: `/api/nodes/bootstrap.sh`, `/api/nodes/register`. Остальные `/api/nodes/**` — внутри контроллера проверяют `X-Node-Secret` вручную (не через Spring Security).

#### Шаг 3. Bootstrap EU-скрипта (`resources/scripts/eu-node-setup.sh`)

Файл UTF-8 без BOM, отдаётся через `renderBytes` (уже умеет).

**Логика скрипта (`curl | sudo bash`):**

1. Проверка `EUID == 0`, иначе exit 1.
2. Определение arch (`uname -m`): `x86_64` → amd64, `aarch64` → arm64.
3. **Полная очистка:**
   - `systemctl stop xray 2>/dev/null; systemctl disable xray 2>/dev/null`
   - `rm -f /etc/systemd/system/xray*.service`
   - `rm -rf /usr/local/xray /opt/xray /etc/xray /var/log/xray`
   - `rm -f /usr/local/bin/xray`
   - `crontab -l | grep -v pohr-agent | crontab -` (убрать старые cron-записи)
   - `systemctl daemon-reload`
4. **Очистка firewall правил, которые могли остаться:**
   - UFW: `ufw delete allow 8443/tcp 2>/dev/null || true`
   - iptables: `iptables -D INPUT -p tcp --dport 8443 -j ACCEPT 2>/dev/null || true`
   - Не трогаем SSH-порт (22/2222 и т.п.) — только правило Xray-порта.
5. **Скачивание Xray:** `curl -sL https://github.com/XTLS/Xray-core/releases/download/v${XRAY_VERSION}/Xray-linux-${ARCH}.zip` → `/tmp/xray.zip` → `unzip` → `/usr/local/xray/xray` → `chmod +x`.
6. **Генерация keypair локально:**
   ```bash
   OUTPUT=$(/usr/local/xray/xray x25519)
   PRIVATE_KEY=$(echo "$OUTPUT" | grep -E 'PrivateKey|Private key' | awk '{print $NF}')
   PUBLIC_KEY=$(echo "$OUTPUT" | grep -E 'Password|Public key' | awk '{print $NF}')
   SHORT_ID=$(openssl rand -hex 8)
   echo "$PRIVATE_KEY" > /etc/xray/private.key
   chmod 600 /etc/xray/private.key
   ```
7. **Определение внешнего IP:** `curl -s https://api.ipify.org`.
8. **Enroll:**
   ```bash
   RESPONSE=$(curl -s -X POST $RU_URL/api/nodes/register \
     -H "Content-Type: application/json" \
     -d "{\"token\":\"$ENROLL_TOKEN\",\"host\":\"$PUBLIC_IP\",\"port\":8443,\"publicKey\":\"$PUBLIC_KEY\",\"shortId\":\"$SHORT_ID\",\"xrayVersion\":\"$XRAY_VERSION\"}")
   NODE_ID=$(echo $RESPONSE | jq -r .nodeId)
   NODE_SECRET=$(echo $RESPONSE | jq -r .nodeSecret)
   CONFIG=$(echo $RESPONSE | jq -r .config)
   ```
   Если `jq` нет — установить (`apt install jq` / `yum install jq`).
9. **Записать конфиг:**
   - `mkdir -p /etc/xray`
   - `echo "$CONFIG" | sed "s/__USE_LOCAL__/$PRIVATE_KEY/" > /etc/xray/config.json`
   - Сохранить `nodeId`, `nodeSecret`, `ruUrl` в `/etc/pohr/node.json`.
10. **Создать systemd unit** `/etc/systemd/system/xray.service`:
    ```ini
    [Unit]
    Description=Xray Service
    After=network.target
    [Service]
    Type=simple
    ExecStart=/usr/local/xray/xray run -c /etc/xray/config.json
    Restart=on-failure
    RestartSec=5
    LimitNOFILE=65536
    [Install]
    WantedBy=multi-user.target
    ```
11. **Firewall:** открыть `8443/tcp` через `ufw allow 8443/tcp` или `iptables -I INPUT -p tcp --dport 8443 -j ACCEPT`.
12. **SSH ключи:** `{{SSH_KEYS}}` (multiline) → добавить в `/root/.ssh/authorized_keys` и `~/.ssh/authorized_keys` пользователя `$SUDO_USER`. Дедуп.
13. **Установить pohr-agent** (см. шаг 4): скачать с `{{RU_URL}}/api/nodes/agent.sh?token=$NODE_ID`, положить в `/usr/local/bin/pohr-agent.sh`, chmod +x, добавить в cron.
14. **Health-check:** `sleep 3 && systemctl is-active xray && ss -tlnp | grep 8443`. Если не активно — `journalctl -u xray -n 50 --no-pager` в stderr и `exit 2`.

**Хранение конфига скрипта:**
- `resources/scripts/eu-node-setup.sh` — бандл в образе (seed в volume, как client scripts).
- Плейсхолдеры: `{{ENROLL_TOKEN}}`, `{{RU_URL}}`, `{{SSH_KEYS}}`, `{{XRAY_VERSION}}`, `{{NODE_PORT}}`.
- `renderBytes` для `.sh` — UTF-8 (дефолт).

**Безопасность:**
- Токен через `?token=` в query — HTTPS обязателен в прод.
- Логи скрипта писать в `/var/log/pohr-setup.log`.

#### Шаг 4. Polling + health (agent.sh + RU-side tunnel check)

**`resources/scripts/pohr-agent.sh`** — устанавливается на EU, cron каждые 5 минут:

1. Прочитать `/etc/pohr/node.json` → `nodeId`, `nodeSecret`, `ruUrl`.
2. `curl -sI -H "X-Node-Secret: $NODE_SECRET" -H "If-None-Match: $CURRENT_HASH" "$RU_URL/api/nodes/$NODE_ID/config"`.
3. Если `304` — ничего. Если `200` — скачать JSON, подставить `privateKey` из `/etc/xray/private.key` вместо `__USE_LOCAL__`, провалидировать (`xray run -test -c /tmp/config.json`), atomic write → `mv /tmp/config.json /etc/xray/config.json`, `systemctl restart xray`.
4. Собрать статус: `systemctl is-active xray`, `xray -version`, hash нового конфига, `uptime`.
5. `curl -s -X POST -H "X-Node-Secret: $NODE_SECRET" -d '{"status":"...","xrayVersion":"...","configHash":"...","uptime":N}' "$RU_URL/api/nodes/$NODE_ID/health"`.
6. При 5xx/таймауте — 3 retry с backoff. Логи в syslog.

**RU-side активная проверка туннеля (`TunnelHealthCheckService`):**
- `@Scheduled(fixedDelayString = "${pohr.tunnel-check.interval:300000}")` — каждые 5 минут.
- Для каждой ноды в статусе `HEALTHY` или `DEGRADED`:
   - SOCKS5-запрос на `127.0.0.1:10808` (healthcheck inbound).
   - HTTP GET `https://api.ipify.org` через этот SOCKS.
   - Ответ == `node.host` → OK, обновить `last_tunnel_ip`, `last_tunnel_check_at`.
   - Ответ == RU-IP → `DEGRADED`.
   - Timeout/ошибка → `UNREACHABLE`.

**Healthcheck inbound** добавляется материализатором в RU-конфиг:
```json
{
  "listen": "127.0.0.1",
  "port": 10808,
  "protocol": "socks",
  "settings": {"auth": "noauth", "udp": false},
  "tag": "healthcheck-socks"
}
```
Routing: `inboundTag: ["healthcheck-socks"] → outboundTag: "eu"`.

**Порог деградации:** 3 подряд провала → `UNREACHABLE`. 1 успех из 3 → `DEGRADED`. 3 из 3 → `HEALTHY`.

**Config:**
```yaml
pohr:
  eu-nodes:
    enabled: true
    health-check-interval: 5m
    tunnel-socks-port: 10808
    tunnel-check-url: https://api.ipify.org
    enroll-token-ttl: 1h
```

#### Шаг 5. Admin UI для нод

**Контроллер `AdminEuNodeController`** (`/admin/nodes`):

- `GET /admin/nodes` — список: name, host, status (badge), last health, last tunnel IP, actions.
- `GET /admin/nodes/new` — форма: `name`, TTL (default 1h). После создания — показать готовую curl-команду **один раз**:
  ```
  curl -sL http://ru.example:8080/api/nodes/bootstrap.sh?token=XXX | sudo bash
  ```
   + копирование (как subscription URL).
- `GET /admin/nodes/{id}` — детально: все поля, кнопки `Re-enroll` (новый токен), `Delete` (только если `PENDING`/`UNREACHABLE` — иначе confirmation), `Rotate tunnel`.
- `POST /admin/nodes/{id}/delete` — удалить ноду + инвалидировать все её токены.
- `POST /admin/nodes/{id}/re-enroll` — сгенерировать новый токен для переустановки EU.

**Шаблоны:** `admin/eu-nodes.html`, `admin/eu-node-form.html`, `admin/eu-node-detail.html`.

**i18n ключи:** `admin.nodes.title`, `admin.nodes.new`, `node.list.name`, `node.list.status`, `node.form.ttl`, `node.detail.enroll-command`, `node.status.pending/healthy/degraded/unreachable`.

**На `/admin`** — новая секция «EU nodes» с бейджем количества `HEALTHY/N`.

#### Шаг 6. Dockerfile + раскатка RU-моста

**Multi-stage `Dockerfile`:**

```dockerfile
# Stage 1: build native
FROM ghcr.io/graalvm/native-image-community:25 AS builder
WORKDIR /build
COPY gradlew gradlew.bat settings.gradle build.gradle gradle.properties ./
COPY gradle gradle
COPY src src
RUN ./gradlew nativeCompile --no-daemon

# Stage 2: runtime (fat image — с Xray внутри)
FROM alpine:3.20
RUN apk add --no-cache curl unzip jq tzdata \
    && adduser -D -u 1000 pohr
WORKDIR /app

ARG XRAY_VERSION=latest
RUN mkdir -p /app/xray/bin \
    && ARCH=$(uname -m | sed 's/x86_64/64/;s/aarch64/arm64-v8a/') \
    && curl -sL "https://github.com/XTLS/Xray-core/releases/download/v${XRAY_VERSION}/Xray-linux-${ARCH}.zip" -o /tmp/x.zip \
    && unzip /tmp/x.zip -d /app/xray/bin \
    && chmod +x /app/xray/bin/xray \
    && rm /tmp/x.zip

COPY --from=builder /build/build/native/nativeCompile/pohr /app/pohr
COPY --from=builder /build/src/main/resources/scripts /app/scripts-bundled

RUN mkdir -p /app/data /app/scripts /app/xray/config \
    && chown -R pohr:pohr /app

USER pohr
EXPOSE 8080
ENV SCRIPTS_HOME=/app/scripts \
    XRAY_HOME=/app/xray \
    SPRING_PROFILES_ACTIVE=prod

ENTRYPOINT ["/app/pohr"]
```

**`docker-compose.prod.yml`:**
```yaml
services:
  pohr:
    image: roclh/pohr:latest
    ports:
      - "8080:8080"
    volumes:
      - pohr-data:/app/data
      - pohr-xray:/app/xray
      - pohr-scripts:/app/scripts
    environment:
      - SPRING_PROFILES_ACTIVE=prod
      - POHR_PUBLIC_URL=${POHR_PUBLIC_URL}
      - POHR_SSH_AUTHORIZED_KEYS=${POHR_SSH_AUTHORIZED_KEYS}
      - XRAY_AUTO_INSTALL=false
    restart: unless-stopped
volumes:
  pohr-data:
  pohr-xray:
  pohr-scripts:
```

**`install.sh` для RU-моста** (в репо, не в образе):
- Проверка docker + docker-compose.
- Создать `.env` со сгенерированными паролями.
- `docker compose -f docker-compose.prod.yml up -d`.

**`POHR_SSH_AUTHORIZED_KEYS`** — multiline env var. В `application-prod.yml`: `pohr.ssh.authorized-keys: ${POHR_SSH_AUTHORIZED_KEYS:}`.

#### Открытые вопросы по EU-нодам

1. **Healthcheck inbound на RU.** Отдельный SOCKS на `127.0.0.1:10808` — лишний inbound в конфиге. Альтернатива — gRPC stats. Для MVP — SOCKS.
2. **Переключение между несколькими EU.** Схема заложена под N нод, но `XrayConfigMaterializer` берёт **первую HEALTHY** по `enrolledAt DESC`. Приоритезация по `priority` — позже.
3. **Удаление EU-ноды с активными соединениями.** При `DELETE` — RU рематериализует конфиг без EU, перезапускает Xray. Юзеры теряют VPN на 1-2 сек.
4. **nodeSecret rotation.** Пока не делаем. При подозрении — re-enroll.
5. **Мониторинг «просроченных» enroll-токенов.** `@Scheduled` чистит `enrollment_tokens` где `expires_at < now()` и `used_at IS NULL`.
6. **SSH keys — где хранить?** MVP — env var `POHR_SSH_AUTHORIZED_KEYS`. Позже — таблица `ssh_keys` с CRUD UI.

#### Порядок реализации

1 → 2 → 3 → 4 → 5 → 6. UI (шаг 5) можно параллельно с 3-4, API уже стабилен. Docker (шаг 6) — в самом конце.

**Проверка на каждом шаге:**
- После шага 3 — `curl | bash` на реальном EU-VPS, `/etc/xray/config.json` создан, Xray запущен, RU видит ноду.
- После шага 4 — `pohr-agent` в cron обновляет health, `TunnelHealthCheckService` пишет `last_tunnel_ip` == EU-IP.

---

### Приоритет 1: добить процесс Xray

- [ ] **Проверка порта перед `pb.start()`** — если порт из активного конфига занят чужим процессом, падать с понятным сообщением, а не `bind: address already in use`.
- [ ] **Автоматический рестарт Xray** при активации конфига. Опциональный чекбокс «apply immediately» в форме редактирования конфига.
- [ ] **`api inbound` + `stats` в дефолтном конфиге** — подготовка к gRPC и подсчёту трафика.

### Приоритет 2: клиентские установщики — надёжность

- [ ] **Детект версии v2rayN по структуре конфига** (`CoreBasicItem` есть → v7.x; иначе v6.x и ниже).
- [ ] **Проверка запуска v2rayN после правки.** Скрипт запускает процесс, ждёт 5 сек, проверяет `Get-Process v2rayN`. Если упал — автоматически восстанавливает из pristine.
- [ ] **Параметр `-Fragment on|off|keep`.** По умолчанию `on`. Некоторым провайдерам фрагмент мешает.
- [ ] **Идемпотентный рестарт**: если конфиг содержит ту же подписку с тем же URL, не перезаписывать.
- [ ] **Список изменений в конфиге для пользователя.** Перед `Start-Process` выводить: что включили, куда положили бэкап.
- [ ] **Отдельная диагностическая команда.** `.\v2rayn-setup.ps1 -Diagnose` — показывает найденные пути, состояние фрагмента и подписки, не правит конфиг.

### Приоритет 3: клиентские установщики — расширение

- [ ] **DNS-настройки в v2rayN.** `SimpleDNSItem`: `RemoteDNS=https://cloudflare-dns.com/dns-query`, `DirectDNS`, `BootstrapDNS`, `BlockAAAAQuery`.
- [ ] **TUN-режим.** `TunModeItem.EnableTun`, `Stack=gvisor`.
- [ ] **Системный прокси.** `SystemProxyItem.SysProxyType`, `SystemProxyExceptions`.
- [ ] **Routing rules из БД** (см. Приоритет 5). После появления — подключать подписку на правила.
- [ ] **«Готовый ZIP»** с уже настроенным v2rayN как альтернатива `.bat`.

### Приоритет 4: мобильные клиенты

- [ ] **Android — Happ.** `happ://add/{SUB_URL}` для подписки + отдача JSON с routing-правилами по отдельному endpoint. Прямое редактирование настроек невозможно без рута.
- [ ] **Android — v2rayNG.** `v2rayng://install-sub/?url={SUB_URL}`. Direct-edit не поддерживается.
- [ ] **iOS — Streisand / Karing / Shadowrocket.** Deep-link схемы: `streisand://import/URL#NAME`, `karing://install-config?url=URL&name=NAME`, `sub://URL`. Для Karing — отдавать `.yaml`/`.json` с правилами; для Shadowrocket — `.conf` (Clash-формат).
- [ ] **QR-код на `/home`.** Генерировать `vless://` в QR-картинку. Библиотека — ZXing или QRose.
- [ ] **Страница `/setup`** — пошаговая инструкция с deep-link кнопками для каждой платформы.

### Приоритет 5: маршрутизация и правила

- [ ] **Routing rules в БД** — по аналогии с `xray_configs`. Несколько профилей («ru-direct», «ru-blocked-only», «whitelist»). Сейчас хардкод в `RoutingRulesController`.
- [ ] **Routing subscription URL в v2rayN.** После появления БД — подключить как `RoutingBasicItem.RoutingIndexId` (точное имя поля уточнить экспериментально).
- [ ] **Готовые routing-профили для Happ и Karing** — отдавать по `/sub/{token}/routing.json` в формате каждого клиента.

### Приоритет 6: масштабирование

- [ ] **gRPC API Xray.** Вместо перезапуска процесса — `HandlerService.AlterInbound` с `AddUserOperation` / `RemoveUserOperation`. Мгновенное добавление пользователей без обрыва соединений.
- [ ] **Health-check endpoint'ов.** `/api/health` расширить: статус EU-узлов, состояние Xray.
- [ ] **Ping/health-check активного EU-узла** — с `/admin/xray` проверять, отвечает ли `pbk`/порт с публичного адреса.

### Приоритет 7: развёртывание

- [ ] **`install.sh` для RU-моста.** Скачивает Docker-образ, генерирует секреты, запускает контейнер.
- [ ] **Self-steal маскировка.** Nginx с сайтом-заглушкой на `127.0.0.1:8443`, Xray `dest` туда же.
- [ ] **Зеркало GitHub.** Кэширование релизов Xray на RU-мосте для обхода блокировок.

### Приоритет 8: UX и удобство

- [ ] **Telegram-бот.** Команды `/start`, `/config`, `/qr`, `/status`. Push при обновлении клиентского скрипта.
- [ ] **Версионирование клиентского конфига.** Хранить в БД `client_config_version`, инкрементировать при изменениях фрагмента/routing. Триггер для TG-пуша.
- [ ] **Смена пароля через UI.** Пользователь меняет свой пароль на `/home`.
- [ ] **Диагностика.** Страница `/status` для пользователя: активен ли EU-узел, когда обновлялась подписка.
- [ ] **Даты в UI** — обрезать наносекунды (`2026-10-01T14:31:05`).

### Приоритет 9: безопасность и приватность

- [ ] **Self-registration с invite-токенами.**
- [ ] **Лимиты трафика и скорости.**
- [ ] **Аудит действий.**
- [ ] **Rate limiting** на `/sub/{token}`.

### Приоритет 10: высший пилотаж

- [ ] **GitHub-коммит скриптов из UI.** Кнопка «Push to GitHub» в форме редактирования скрипта. Через GitHub API: создать branch, закоммитить файл, открыть PR. Требует fine-grained PAT в env.
- [ ] **Синхронизация скриптов между инстансами Pohr.** Экспорт/импорт скриптов через API.

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

---

## 📋 Открытые вопросы

1. **gRPC API Xray** — до или после EU-нод. Если до — `AlterInbound` упростит ротацию `clients[]` при добавлении EU. Если после — при enroll EU придётся рестартить Xray на RU. Склоняюсь к «после EU».
2. **`XRAY_HOST` для подписок после EU.** После EU-схемы — **всё равно RU-host** (клиент → RU → EU). Убедиться, что `XrayConfigService.buildVlessLinks` не подхватит EU-host.
3. **Ротация subscription-токенов.** Ручная (delete + create) или автоматическая раз в N дней?
4. **Мультитенантность.** Один админ или несколько?
5. **PostgreSQL как альтернатива SQLite** — для больших нагрузок.
6. **Стратегия сокрытия.** Простая (self-steal) или радикальная (SSH-инверсия RU → EU)?
7. **Routing rules из БД** — делать сейчас или отложить до мобильных клиентов?
8. **Единый формат клиентских конфигов.** Ввести «абстрактный» клиентский профиль в БД (JSON с фрагментом/DNS/routing), из которого каждый установщик рендерит свой формат? Или per-client скрипты отдельно? Склоняюсь к первому.
9. **Обновление клиентских скриптов у пользователей.** Как уведомить: TG-бот, email, или баннер на `/home`?
10. **Android без рута** — принимаем ограничение (только подписка + deep-link), или пробуем через ADB (нереалистично)?
11. **Если EU-нода становится UNREACHABLE с активными юзерами.** Сейчас — трафик просто ляжет. Хочется fallback на freedom+geoip:ru direct. Позже.
12. **Backup приватного ключа EU.** Если VPS сгорит, `privateKey` потерян → re-enroll. Автоматизация не нужна, но в UI ноды — напоминание «бэкап /etc/xray/private.key».
13. **Срок жизни `nodeSecret`.** Пока бессрочный. Идея: ротировать раз в 30 дней автоматически через `/rotate-tunnel`.

---

## 🎬 Как начать новый диалог

Привет. Продолжаем разработку **Pohr** — системы управления VPN-инфраструктурой для обхода блокировок РКН.

Текущий статус (см. полный контекст ниже):
- Сделано: Xray-конфиги в БД + материализация, per-user UUID, xhttp+Reality, редактор конфигов, клиентские скрипты в volume с seed'ом из бандла, рабочий v2rayN-установщик (фрагмент, per-user подписка, pristine-бэкапы, `-Restore`).
- В работе (P0): EU-ноды — enrollment API, bootstrap-скрипт, polling+health, admin UI, fat Docker image.
- Предстоит: EU-ноды (шаги 1-6), надёжность v2rayn-setup, расширение настроек клиента (DNS, TUN, routing), мобильные клиенты (Happ/v2rayNG/Streisand/Karing/Shadowrocket), routing rules в БД, gRPC Xray.

Стек: Java 25 + Spring Boot 4.0.8 + GraalVM Native + SQLite + Liquibase + Thymeleaf + Spring Security + Xray-core.

Правила: DTO — record в `model/dto`, сущности в `model`, Liquibase через SQL-миграции, `ddl-auto: validate`, id — UUID, время — `Instant` через `InstantStringConverter`, **Xray-конфиг в БД, файл — материализация**, **клиентские скрипты в volume с seed'ом из бандла, редактор в UI**, **EU-ноды — pull-модель, EU генерит свои ключи локально, RU видит только publicKey**.

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