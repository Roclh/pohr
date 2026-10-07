
## 1. Философия

- **Скрипты — это активы системы.** Они версионируются, каталогизируются, отдаются по единому API, отслеживаются.
- **Версия — это метаданные, а не код.** Внутри файла нет `@version`. Версия живёт в БД и доставляется клиенту отдельно.
- **Всё делается через UI.** Админ взаимодействует с системой через UI. Файловая система — не интерфейс для пользователя, а хранилище.
- **Никаких «умных» автоматик без явного контроля.** Мажор бампается вручную. Rename — вручную. Seed-обновления не перезатирают правки.
- **Клиент не анализирует содержимое скриптов.** Он только передаёт то, что получил от сервера (в заголовках, в аргументах, в placeholder-подстановках).

---

## 2. Каталог скриптов

### 2.1 Реестр

Все скрипты регистрируются в БД по имени файла. Регистрация происходит при старте приложения (`ensureRegistered`) и при сохранении через UI.

**Поля `scripts`:**
- `name` — имя файла (PK), напр. `v2rayn-setup.ps1`
- `display_name` — человекочитаемое
- `description` — что делает
- `platform` — `windows` | `linux` | `macos` | `any`
- `access_level` — `PUBLIC` (клиент может скачать) | `ADMIN` (только админ)
- `is_entrypoint` — точка входа. Флаг ставится **вручную** админом. Если нет — не считаем.
- `sort_order`, `enabled`, `created_at`, `updated_at`
- `is_seeded`, `seed_version` — для отслеживания «этот файл мы сеем из classpath, а не админ создал»

### 2.2 Регистрация из ФС

При старте сканируется `SCRIPTS_HOME` и `resources/scripts/`. Для каждого файла:
- Если файла нет в БД → регистрация с версией `1.0`, `source = 'volume-rescan'` (или `'seed'`, если из classpath).
- Если файл есть в БД и хэш совпадает → no-op.
- Если файл есть в БД, но хэш не совпадает → **новый bump**, `source = 'volume-rescan'`.
- Если файл есть в БД, но нет в ФС → `enabled = false`, не удаляем запись.

### 2.3 Seed

Classpath-скрипты — бандл. При первом старте копируются в volume. Если файл уже есть в volume — **не перезаписывается**. `is_seeded = true`, `seed_version = 1.0`.

Если при обновлении Pohr classpath-версия изменилась, а в volume старая — **автоматически не перезаписываем**. Показываем в `/admin/scripts` бейдж «seed устарел» и кнопку «Re-seed».

### 2.4 Rename

Только через UI. Файловая система не используется для переименований. При переименовании в UI версия и история сохраняются.

---

## 3. Версионирование

### 3.1 Формат

`MAJOR.MINOR`, оба целые числа. `1.0`, `1.1`, `2.0`, `1.17`. Арифметический инкремент (1.9 → 1.10, не 1.10 «меньше 1.9»).

### 3.2 Правила bump

- **Minor: автоматически.** Любое изменение содержимого файла — инкремент минорной. Админ не управляет минорной.
- **Major: вручную.** Админ жмёт «Bump major» — минорная сбрасывается в 0.
- **При первом seed** — `1.0`.
- **При rollback** — bump минорной (файл изменился → новая версия), `restore_of` ссылается на исходную версию.
- **При появлении нового файла в volume** — `1.0`.

### 3.3 Где хранится версия

**Только в БД.** В файлах — нет. Доставка клиенту:
- HTTP-заголовки (`X-Pohr-Script-Version`).
- Placeholder `{{__VERSION__}}` в теле файла, подставляется сервером при отдаче.

### 3.4 История версий

Каждая версия — запись в `script_versions`:
- `id`, `script_id`, `version`, `hash`, `size`, `created_at`, `invalidated_at`
- `snapshot_path` — путь к `.history/{name}.{version}.{timestamp}.bak`
- `source` — `seed` | `ui-edit` | `volume-rescan` | `rollback`
- `restore_of` — FK на `script_versions.id` (если это rollback)

**Ротация файлов в `.history/`:**
- Последние 5 версий.
- Плюс последняя версия каждого предыдущего мажора.
- Объединение без дубликатов.

**В БД:** строки не удаляются, только помечаются `invalidated_at`. Это нужно для отображения истории и для rollback.

### 3.5 Rollback

Rollback — всегда на **конкретную версию конкретного скрипта**. Никаких каскадов.
1. Копируем `.history/{name}.{version}.bak` → рабочий файл.
2. Bump минорной (стандартное правило).
3. Записываем `restore_of`.
4. Все клиенты при следующем sync увидят обновление.

---

## 4. Placeholder `{{__VERSION__}}`

### 4.1 Назначение

Позволяет скрипту «знать свою версию» без версии в исходнике. В файле на диске стоит `{{__VERSION__}}`. При отдаче сервер заменяет его на актуальную версию из БД.

### 4.2 Инжект

- **При seed'е** — сервер гарантирует, что placeholder есть в файле.
- **При UI-edit** — если placeholder в файле потерялся, UI предупреждает: «Placeholder {{__VERSION__}} не найден, версия не будет подставлена».
- **В utils** — `$script:PohrUtilsVersion = "{{__VERSION__}}"`, сервер подставит актуальное значение.

### 4.3 Fallback

Если placeholder в файле нет — версия не подставляется в тело, но:
- HTTP-заголовок `X-Pohr-Script-Version` всё равно уходит.
- Клиент (`.bat`/`.ps1`) может прочитать версию из заголовка.
- Если клиент вообще не смог определить свою версию — отправляет `unknown`, сервер подставляет последнюю скачанную им версию из `script_downloads`.

---

## 5. Доставка

### 5.1 Эндпоинты

```
GET  /sub/{token}/scripts                    -- каталог (admin-only, HTML)
GET  /sub/{token}/scripts/{name}             -- preview (HTML, безопасно)
GET  /sub/{token}/scripts/{name}/raw         -- скачивание (файл)
GET  /sub/{token}/scripts/{name}/meta        -- метаданные (JSON)
POST /sub/{token}/scripts/sync               -- отчёт о версиях
POST /sub/{token}/scripts/error-report       -- отчёт об ошибке
```

**Инверсия:** `/scripts/{name}` — preview, `/scripts/{name}/raw` — файл. Ручной ввод URL не приводит к случайному скачиванию.

### 5.2 Заголовки `/raw`

```
X-Pohr-Script-Name:    v2rayn-setup.ps1
X-Pohr-Script-Version: 2.1
X-Pohr-Script-Hash:    sha256:abc123...
X-Pohr-Rendered-At:    2026-10-07T15:00:00Z
```

### 5.3 Плейсхолдеры в шаблоне

Сервер при отдаче резолвит:
- `{{BASE_URL}}` — базовый URL
- `{{TOKEN}}` — токен подписки
- `{{SUBSCRIPTION_URL}}` — `{BASE_URL}/sub/{TOKEN}`
- `{{RULES_URL}}` — `{BASE_URL}/sub/{TOKEN}/rules.json`
- `{{SCRIPTS_BASE_URL}}` — `{BASE_URL}/sub/{TOKEN}/scripts`
- `{{__VERSION__}}` — версия отдаваемого скрипта
- `{{PS1_URL}}`, `{{UTILS_URL}}` — BC-алиасы на новые URL

### 5.4 Логирование скачиваний

Каждое скачивание `/raw` логируется в `script_downloads`:
- `user_id`, `script_name`, `version`, `downloaded_at`, `client_ip`, `user_agent`

Нужно для fallback'а (когда клиент не смог определить свою версию) и для аналитики.

Ротация: 1000 записей на пользователя или 2 недели.

---

## 6. Зависимости

### 6.1 Детект

**Не хранятся в БД.** Вычисляются на лету при запросе.
- Регулярка по содержимому: ищутся упоминания имён файлов из реестра скриптов.
- Ссылки вида `/scripts/{name}/raw`, `/scripts/{name}`, а также резолвленные `{{UTILS_URL}}` и т.п.
- Ложные срабатывания допустимы (упоминание в комментарии).
- Циклы игнорируются при обходе (BFS с посещёнными).

### 6.2 Кэш

In-memory кэш `Map<scriptName, Set<depName>>`, инвалидируется при:
- Любом bump версии.
- Изменении содержимого.
- Регистрации нового скрипта.
- Удалении / disable скрипта.

Граф маленький (4–5 ветвей), пересчёт быстрый.

### 6.3 Использование

- **Update-check** на `/home`: для entrypoint считаем транзитивные зависимости, сравниваем с версиями пользователя.
- **Диагностика** в `/admin/scripts/{name}`: показывает, кто зависит от этого скрипта.

### 6.4 Никакой пропагации версий

Изменение зависимости **не** bump'ает родителя. Родитель обновляется только когда меняется его собственный файл.

---

## 7. Update-check

### 7.1 Логика

На `/home` для каждого entrypoint:
1. Считаем транзитивный набор зависимостей (`A → {B, C}`, транзитивно).
2. Для каждой зависимости сравниваем серверную версию с версией пользователя (из `user_script_versions`).
3. Если расхождение:
    - **Major** разница (`1.x → 2.x`) → **яркое** уведомление.
    - **Minor** разница (`1.5 → 1.6`) → **серый** бейдж, без всплывающих окон.
4. Если всё совпадает — ничего не показываем.

### 7.2 Отображение

- **Серый бейдж:** рядом с панелью на `/home`, текст вида `1.5 → 1.6`. Никаких модалок.
- **Яркое уведомление:** заметный бейдж (красный/оранжевый), опционально — разовая модалка при заходе. Пользователь может отложить.

### 7.3 Мажорность

Определяется по **версии зависимости**, не по корню. Если `utils.ps1` ушёл с 1.4 на 2.0 — яркое. Если `v2rayn-setup.ps1` ушёл с 1.5 на 1.6 — серое.

---

## 8. Sync (отчёт о версиях)

### 8.1 Payload

```json
{
  "rootScript": "v2rayn-setup.bat",
  "versions": {
    "v2rayn-setup.bat": "1.5",
    "v2rayn-setup.ps1": "2.1",
    "pohr-utils.ps1":   "1.3"
  },
  "reportedAt": "2026-10-07T15:00:00Z"
}
```

### 8.2 Обработка

1. Проверяем, что `rootScript` — entrypoint.
2. Upsert в `user_script_versions` по `(user_id, script_name)`.
3. Append в `script_version_reports`, **только если версия изменилась** с прошлой записи.
4. Ответ:
```json
{
  "latest": {
    "v2rayn-setup.bat": "1.5",
    "v2rayn-setup.ps1": "2.2",
    "pohr-utils.ps1":   "1.4"
  },
  "updatesAvailable": ["v2rayn-setup.ps1", "pohr-utils.ps1"]
}
```

### 8.3 Когда клиент отправляет

- После каждого успешного применения скрипта.
- Даже если версии не изменились — сервер сам дедуплицирует.

### 8.4 Как клиент узнаёт версии

**`.bat` (скачан пользователем):**
- Своя версия — из placeholder-подстановки (`set "SCRIPT_VERSION=1.5"`).
- Если placeholder отсутствовал → `unknown`.

**`.bat` передаёт `.ps1`:**
```
-File "%TEMP_PS1%" -RootName "v2rayn-setup.bat" -RootVersion "%SCRIPT_VERSION%"
```

**`.bat` качает `.ps1`:**
- Читает `X-Pohr-Script-Version` из заголовка.
- Передаёт в `.ps1`: `-Ps1Version "2.1"`.

**`.ps1` качает utils:**
- Читает версию из заголовка.
- Собирает итоговый map.

**`.ps1` отправляет sync.**

### 8.5 Что если `.ps1` крашнулся

`.ps1` не отправил sync. `.bat` ловит `%ERRORLEVEL% != 0` и отправляет `/error-report` с минимальными данными.

---

## 9. Error reports

### 9.1 Payload

```json
{
  "rootScript": "v2rayn-setup.bat",
  "rootVersion": "1.5",
  "stage": "patch-config",
  "exception": "System.ArgumentException: ...",
  "stackTrace": "...",
  "os": "Windows 10.0.19045",
  "psVersion": "5.1.19041.1",
  "v2raynVersion": "7.2.4",
  "configPath": "C:\\v2ray\\v2rayn\\guiNConfig.json",
  "logTail": "...последние 30 строк pohr.log...",
  "extra": "{}"
}
```

### 9.2 Согласие пользователя

При ошибке `.ps1` показывает пользователю:
> «Произошла ошибка. Отправить отчёт разработчику для диагностики? Включит: версию ОС, PowerShell, v2rayN, текст ошибки, последние строки лога. Персональные данные не отправляются.»
> `[Y/n]`, default `Y`.

Если пользователь отказался — ошибка всё равно показывается, `exit 1`, отчёт не отправляется.

### 9.3 Сбор на стороне `.ps1`

Если упало — собираем максимум, что можем:
- `$PSVersionTable.PSVersion`
- `[System.Environment]::OSVersion`
- Версия v2rayN (из реестра или exe)
- Путь до `guiNConfig.json`, размер
- Последние 30 строк `%LOCALAPPDATA%\Pohr\pohr.log`
- Exception type, message, stack trace
- Текущий шаг выполнения

### 9.4 Fallback через `.bat`

Если `.ps1` упал жёстко (нет `try/catch`, PowerShell engine крашнулся):
- `.bat` ловит `%ERRORLEVEL% != 0`.
- Отправляет минимальный отчёт: `{stage: "ps1-crashed", exitCode, psVersion, osVersion}`.
- HTTP inline (без utils, потому что utils могли не загрузиться).

### 9.5 Хранение

- Таблица `script_error_reports`: `id`, `user_id`, `root_script`, `script_version`, `status`, `report_text`, `client_ip`, `user_agent`, `received_at`, `resolved_at`, `resolved_by`.
- **Plaintext** (не сжимаем, не шифруем).
- При `resolved` — **удаляется**, агрегат (`daily_stats`) сохраняется.
- Retention: авто-удаление через 30 дней, если не разобран.

### 9.6 Доступ

- **Админ:** `/admin/scripts/reports` — все, фильтры.
- **Пользователь:** `/sub/{token}/reports` — только свои, со статусом.

---

## 10. API-модель доступа

- `/sub/{token}/scripts` — **только для админа.** Обычный клиент сюда не ходит.
- `/home` — entrypoint-кнопки. Клик → `/raw`. Eye-иконка → preview.
- `/sub/{token}/scripts/{name}` — preview. Для PUBLIC-скриптов доступен с токеном. Для ADMIN-скриптов — только админ.
- `/sub/{token}/scripts/{name}/raw` — аналогично.

---

## 11. UI

### 11.1 `/home`

- Панели entrypoint-скриптов.
- Клик по панели → скачивание (или deep-link, если это `happ://`).
- **Eye-иконка** — только на панелях, ведущих к скачиванию файла (`.bat`, `.ps1`, `.sh`). Не на deep-link'ах.
- Eye-иконка ведёт в preview. Там большая кнопка «Скачать».
- **Бейджи обновлений:**
    - Серый `1.5 → 1.6` при minor.
    - Яркий при major.

### 11.2 `/sub/{token}/scripts` (admin-only)

Каталог: имя, версия, entrypoint, зависимости, статус.

### 11.3 `/sub/{token}/scripts/{name}` (preview)

- Содержимое с нумерацией строк.
- Метаданные: версия, размер, платформа, зависимости.
- Кнопка «Скачать».
- Breadcrumbs.

### 11.4 `/admin/scripts`

Таблица:

| Script | Version | Entry | Deps | Users on latest | Behind | Errors 24h |
|---|---|---|---|---|---|---|

Клик → детали скрипта.

### 11.5 `/admin/scripts/{name}`

- Список версий с датами, хэшами, source.
- Кто использует: `user, version, applied_at`.
- Кнопка **Rollback to vX.Y** для каждой версии.
- Кнопка **Bump major**.
- Граф зависимостей (кто от кого зависит).
- История скачиваний.

### 11.6 `/admin/scripts/reports`

- Список отчётов с фильтрами.
- Клик → `<pre>` с содержимым.
- Кнопки: `in_progress`, `resolved`.

### 11.7 `/sub/{token}/reports` (для клиента)

- Свои отчёты: дата, скрипт, версия, статус.
- Без содержимого (только «отправлено, разбирается, разобрано»).

---

## 12. Схема БД (эскиз)

```
scripts
  id, name, display_name, description, platform, access_level,
  is_entrypoint, sort_order, enabled, created_at, updated_at,
  is_seeded, seed_version

script_versions
  id, script_id, version, hash, size, created_at, invalidated_at,
  snapshot_path, source, restore_of

script_downloads
  id, user_id, script_name, version, downloaded_at,
  client_ip, user_agent

user_script_versions
  user_id, script_name, version, reported_at,
  client_ip, user_agent
  PK (user_id, script_name)

script_version_reports        -- append-only, ротация
  id, user_id, script_name, version, reported_at,
  client_ip, user_agent

script_error_reports
  id, user_id, root_script, script_version,
  status ('new'|'in_progress'|'resolved'),
  report_text, client_ip, user_agent,
  received_at, resolved_at, resolved_by
```

---

## 13. Что НЕ делаем

- ❌ Не пишем версию в исходники скриптов.
- ❌ Не храним граф зависимостей в БД.
- ❌ Не пропагируем версии вверх по зависимостям.
- ❌ Не парсим содержимое на клиенте.
- ❌ Не переименовываем файлы из ФС.
- ❌ Не синхронизируем seed с volume автоматически.
- ❌ Не делаем rollback каскадом.
- ❌ Не шлём heartbeat'ы на каждый чих — только sync по факту применения.

---

## 14. Открытые вопросы

Помечаю, что осталось решить — но не блокирует старт.

1. **Валидация placeholder'а.** UI должен предупреждать, если админ сохраняет файл без `{{__VERSION__}}`? Или молча разрешать (fallback через заголовки сработает)?
2. **Формат `report_text`.** Просто JSON.stringify собранных полей? Или человекочитаемый? Склоняюсь к структурированному JSON + заголовок «Report from user X, script Y vZ».
3. **Дневные агрегаты по error reports.** Что именно хранить в `daily_stats`? Только count? Или count + топ-3 exception type?
4. **`script_downloads` retention.** 1000 записей или 2 недели? Или оба условия?
5. **Rate limiting.** Отложено на потом, но надо не забыть для `/sync` и `/error-report`.
6. **Уведомления пользователю о доступных обновлениях.** Показывать бейдж на `/home` — ок. Но если пользователь неделю не заходит — как он узнает? Возможно, отдельное письмо / TG-сообщение — но это уже вне скриптов.

---

## 15. Порядок реализации

Предлагаю такой:

1. **Миграция БД** — все таблицы.
2. **`ScriptCatalogService`** — сканирование, регистрация, хэши, bump, детект зависимостей.
3. **`ScriptVersionService`** — версии, `.history/`, rollback.
4. **API-контроллеры** — `/scripts`, `/raw`, `/meta`, `/sync`, `/error-report`, preview.
5. **UI** — `/home` (eye-иконки + бейджи), `/admin/scripts`, `/admin/scripts/{name}`, `/admin/scripts/reports`, `/sub/{token}/reports`.
6. **Клиентские скрипты** — `pohr-utils.ps1`, переписанный `v2rayn-setup.ps1` (с sync, error-report, placeholder-версией).
7. **`.bat`** — минимальные правки (передать `-RootVersion`, ловить exit code).

Пункты 1–4 можно делать параллельно с 6, но 5 (UI) логичнее после 4. 6 пишем, когда есть `utils`-эндпоинт и `/sync`.
