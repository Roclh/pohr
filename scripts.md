# Pohr: написание клиентских скриптов

Этот документ описывает контракт между сервером Pohr и клиентскими скриптами, инструменты, доступные скрипту, и соглашения. Следуй ему буквально. Если правило противоречит «здравому смыслу» — правило важнее.

---

## 1. Что такое скрипты Pohr

Скрипты — управляемые из UI артефакты, которые пользователь скачивает и запускает на своей машине, чтобы настроить VPN-клиент под подписку Pohr. Скрипты:

- **Версионируются на сервере** (в БД, не в файле).
- **Отдаются через единый API** (`/sub/{token}/scripts/{name}/raw`).
- **Рендерятся на лету** — сервер подставляет плейсхолдеры (`{{TOKEN}}`, `{{SCRIPTS_BASE_URL}}`, `{{__VERSION__}}`).
- **Отчитываются серверу** — после применения скрипт отправляет `sync` (что у него стоит) и при ошибке `error-report`.

Типовой сценарий: пользователь качает `v2rayn-setup.bat`, тот скачивает `v2rayn-setup.ps1`, тот — `pohr-utils.ps1`, применяет настройки к v2rayN, отправляет `sync`. В админке видно, кто на какой версии сидит.

---

## 2. Что сервер даёт скрипту

### 2.1 Endpoints

Все URL строятся относительно `{{SCRIPTS_BASE_URL}}` = `{BASE_URL}/sub/{TOKEN}/scripts`.

| Метод | Путь | Назначение |
|---|---|---|
| `GET` | `/{name}/raw` | Скачать файл скрипта |
| `GET` | `/{name}/meta` | JSON с метаданными (версия, хэш, size, deps) |
| `GET` | `/updates` | Доступные обновления для пользователя |
| `POST` | `/sync` | Отчёт о применённых версиях |
| `POST` | `/error-report` | Отчёт об ошибке |

**Важно:** `/sync` и `/error-report` — обычные POST'ы, без CSRF, без cookies. Аутентификация — токен в URL. Скрипт может их вызывать напрямую.

### 2.2 Заголовки на `/raw`

```
X-Pohr-Script-Name:    v2rayn-setup.ps1
X-Pohr-Script-Version: 2.1
X-Pohr-Script-Hash:    sha256:abc123...
X-Pohr-Rendered-At:    2026-10-07T15:00:00Z
```

**Правило:** если скрипт скачивает другой скрипт, он **обязан** прочитать `X-Pohr-Script-Version` из заголовка и запомнить версию для `sync`.

### 2.3 Плейсхолдеры в теле

Сервер заменяет плейсхолдеры в содержимом **перед отдачей**:

| Плейсхолдер | Значение |
|---|---|
| `{{BASE_URL}}` | `https://pohr.example.com` |
| `{{TOKEN}}` | токен подписки |
| `{{SUBSCRIPTION_URL}}` | `{{BASE_URL}}/sub/{{TOKEN}}` |
| `{{RULES_URL}}` | `{{BASE_URL}}/sub/{{TOKEN}}/rules.json` |
| `{{SCRIPTS_BASE_URL}}` | `{{BASE_URL}}/sub/{{TOKEN}}/scripts` |
| `{{__VERSION__}}` | версия этого скрипта |
| `{{PS1_URL}}`, `{{UTILS_URL}}` | BC-алиасы на конкретные скрипты |

**Правило:** URL других скриптов формируй через `{{SCRIPTS_BASE_URL}}`, а не через `{{PS1_URL}}`/`{{UTILS_URL}}`. Алиасы — только для обратной совместимости.

**Правило:** свою версию всегда сохраняй в переменную из `{{__VERSION__}}`. Если плейсхолдера в файле нет — сервер подставит пустоту, и версия будет `unknown`.

### 2.4 Pohr Utils API

`pohr-utils.ps1` — стандартная библиотека. Скачивается первым шагом, dot-source'ится, даёт функции:

**Логирование:**
- `Write-PohrInfo "msg"` — голубое сообщение + в лог-файл
- `Write-PohrOk "msg"` — зелёное
- `Write-PohrWarn "msg"` — жёлтое
- `Write-PohrError "msg"` — красное
- `Write-PohrDebug "msg"` — серое

**HTTP:**
- `Invoke-PohrHttp -Uri $url -Method GET|POST -Body $json -Headers $h -MaxAttempts 3` — с retry и экспоненциальным backoff.

**Серверное API:**
- `Send-PohrSync -BaseUrl $base -Token $token -RootName $name -Versions $map` — отправить sync.
- `Send-PohrErrorReport -BaseUrl $base -Token $token -RootName $name -RootVersion $v -Stage $stage -Report $text` — отправить отчёт об ошибке.
- `Get-PohrScriptInfo -BaseUrl $base -Token $token -ScriptName $name` — JSON с метаданными скрипта.

**JSON:**
- `Read-PohrJson $path` — прочитать JSON, с валидацией.
- `Write-PohrJson -Object $obj -Path $path` — атомарная запись (tmp + move).

**Транзакции:**
- `Start-PohrTransaction -Name $n -BackupPath $p` — перед изменением файлов.
- `Complete-PohrTransaction` — после успеха.
- `Get-PohrPendingTransaction` — при старте, для recovery.

**Бэкапы:**
- `New-PohrPristineBackup -FilePath $p -Tag "pohr"` — один раз, никогда не перезаписывается.
- `New-PohrTimestampedBackup -FilePath $p -Tag "pohr"` — с ротацией (5 последних).

**UI:**
- `Read-PohrConfirm "msg" -Default $true` — `[Y/n]`.
- `Select-PohrFolder "description"` — FolderBrowserDialog.

**Утилиты:**
- `Get-PohrFileHash $path` — sha256 hex.

**Правило:** не изобретай HTTP-запросы руками, используй `Invoke-PohrHttp`. Не пиши JSON через `Set-Content`, используй `Write-PohrJson`.

---

## 3. Что скрипт должен дать серверу

### 3.1 Sync — после успешного применения

```powershell
$versions = @{
    "v2rayn-setup.bat" = $RootVersion       # пришёл аргументом из .bat
    "v2rayn-setup.ps1" = "{{__VERSION__}}"  # подставлен сервером
    "pohr-utils.ps1"   = $UtilsVersion      # прочитан из заголовка при скачивании
}
Send-PohrSync -BaseUrl "{{BASE_URL}}" -Token "{{TOKEN}}" `
              -RootName "v2rayn-setup.ps1" -Versions $versions
```

**Правило:** sync отправляется **всегда** в конце успешного выполнения. Даже если версия не изменилась — сервер сам дедуплицирует.

**Правило:** `RootName` — имя **entrypoint-скрипта** (того, который пользователь запустил). Даже если sync делает `.ps1`, `RootName` = `v2rayn-setup.bat`.

### 3.2 Error report — при ошибке

```powershell
try {
    # ...работа...
} catch {
    $stage = "patch-config"
    $report = @"
Exception: $($_.Exception.GetType().FullName)
Message:   $($_.Exception.Message)
Stack:     $($_.ScriptStackTrace)
Stage:     $stage
OS:        $([System.Environment]::OSVersion)
PS:        $($PSVersionTable.PSVersion)
"@
    if (Read-PohrConfirm "Произошла ошибка. Отправить отчёт разработчику?" -Default $true) {
        Send-PohrErrorReport -BaseUrl "{{BASE_URL}}" -Token "{{TOKEN}}" `
                             -RootName "v2rayn-setup.bat" -RootVersion $RootVersion `
                             -Stage $stage -Report $report
    }
    exit 1
}
```

**Правило:** отчёт собирается **локально** и отправляется только с согласия пользователя (default Y).

**Правило:** `stage` — короткий идентификатор шага (`load-utils`, `find-client`, `patch-config`, `restart-client`, `ps1-crashed`). Используется для группировки отчётов в админке.

**Правило:** сам отчёт не должен содержать персональных данных (пути к домашней папке — ок, username — нет). Если в отчёт попадёт что-то чувствительное, обрезай.

---

## 4. Обязательный каркас

### 4.1 `.bat` (Windows launcher)

Назначение: скачать `.ps1`, запустить, поймать exit code, отправить минимальный error-report при жёстком крахе.

```bat
@echo off
chcp 866 >nul
setlocal
title Pohr - Setup

set "SCRIPT_NAME=v2rayn-setup.bat"
set "SCRIPT_VERSION={{__VERSION__}}"

set "PS1_URL={{SCRIPTS_BASE_URL}}/v2rayn-setup.ps1/raw"
set "TEMP_PS1=%TEMP%\pohr-v2rayn-setup.ps1"

echo Скачиваю установщик...
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$r = Invoke-WebRequest -Uri '%PS1_URL%' -OutFile '%TEMP_PS1%' -UseBasicParsing -PassThru; $r.Headers['X-Pohr-Script-Version'] | Out-File '%TEMP_PS1%.ver' -Encoding ASCII; exit 0"

if errorlevel 1 (
    echo ОШИБКА: не удалось скачать %PS1_URL%
    exit /b 1
)

set /p PS1_VERSION=<"%TEMP_PS1%.ver"

powershell -NoProfile -ExecutionPolicy Bypass -File "%TEMP_PS1%" ^
  -RootName "%SCRIPT_NAME%" -RootVersion "%SCRIPT_VERSION%" -Ps1Version "%PS1_VERSION%"
set "EXITCODE=%errorlevel%"

del "%TEMP_PS1%" 2>nul
del "%TEMP_PS1%.ver" 2>nul

exit /b %EXITCODE%
```

**Правило:** `.bat` **не анализирует содержимое** — он только загрузчик. Вся логика в `.ps1`.

**Правило:** `.bat` передаёт в `.ps1` три аргумента: `-RootName`, `-RootVersion`, `-Ps1Version`.

**Правило:** если `.ps1` вернул `!= 0`, `.bat` просто делает `exit /b %EXITCODE%`. Расширенный error-report уходит из `.ps1`. Жёсткий краш `.ps1` (без своего `try/catch`) ловится на сервере как «нет sync за N минут», это осознанный компромисс.

### 4.2 `.ps1` (Windows worker)

Назначение: скачать utils, применить изменения, отправить sync или error-report.

```powershell
# @pohr-entrypoint v2rayn-setup.bat

param(
    [Parameter(Mandatory)] [string] $RootName,
    [Parameter(Mandatory)] [string] $RootVersion,
    [string] $Ps1Version = "unknown"
)

$ErrorActionPreference = "Stop"
$PohrScriptName    = "v2rayn-setup.ps1"
$PohrScriptVersion = "{{__VERSION__}}"
$BaseUrl           = "{{BASE_URL}}"
$Token             = "{{TOKEN}}"

$Stage = "load-utils"
$UtilsVersion = "unknown"

try {
    # --- 1. Utils ---
    $utilsUrl = "{{SCRIPTS_BASE_URL}}/pohr-utils.ps1/raw"
    $utilsPath = Join-Path $env:TEMP "pohr-utils.ps1"
    $resp = Invoke-WebRequest -Uri $utilsUrl -OutFile $utilsPath -UseBasicParsing -PassThru
    $UtilsVersion = $resp.Headers["X-Pohr-Script-Version"]
    . $utilsPath

    Write-PohrInfo "Утилиты v$UtilsVersion загружены"

    # --- Recovery ---
    $pending = Get-PohrPendingTransaction
    if ($pending) { ... }

    # --- 2. Доменная логика ---
    $Stage = "find-client"
    # ... поиск v2rayN ...

    $Stage = "patch-config"
    # ... патч guiNConfig.json ...

    # --- 3. Sync ---
    $Stage = "sync"
    $versions = @{
        $RootName        = $RootVersion
        $PohrScriptName  = $PohrScriptVersion
        "pohr-utils.ps1" = $UtilsVersion
    }
    Send-PohrSync -BaseUrl $BaseUrl -Token $Token -RootName $RootName -Versions $versions

    exit 0
} catch {
    $report = @"
Script:   $PohrScriptName v$PohrScriptVersion
Root:     $RootName v$RootVersion
Stage:    $Stage
Exception: $($_.Exception.GetType().FullName)
Message:   $($_.Exception.Message)
Stack:     $($_.ScriptStackTrace)
OS:        $([System.Environment]::OSVersion)
PS:        $($PSVersionTable.PSVersion)
"@
    Write-PohrError "Ошибка на шаге $Stage"

    if (Read-PohrConfirm "Произошла ошибка. Отправить отчёт разработчику?" -Default $true) {
        try {
            Send-PohrErrorReport -BaseUrl $BaseUrl -Token $Token `
                                 -RootName $RootName -RootVersion $RootVersion `
                                 -Stage $Stage -Report $report
        } catch {}
    }
    exit 1
}
```

**Правило:** `$ErrorActionPreference = "Stop"` в начале. Любая ошибка — исключение.

**Правило:** оберни всю логику в `try/catch`. В `catch` — отправка error-report.

**Правило:** `$Stage` меняется перед каждым логическим шагом. Это единственный способ узнать из отчёта, где упало.

**Правило:** `RootName`/`RootVersion` приходят аргументами от `.bat`. Своя версия — из `{{__VERSION__}}`. Версия utils — из заголовка.

**Правило:** sync в конце, exit 0. Если что-то упало — sync не отправляется, только error-report.

### 4.3 `.sh` (Linux)

Аналогично `.ps1`, но на bash. Используй `set -euo pipefail`, `curl -fsSL`, `jq`. Получай версию через `-D -` и `grep`:

```bash
#!/usr/bin/env bash
set -euo pipefail

SCRIPT_VERSION="{{__VERSION__}}"
BASE_URL="{{BASE_URL}}"
TOKEN="{{TOKEN}}"

stage="init"

send_error() {
    local report="$1"
    curl -fsSL -X POST "$BASE_URL/sub/$TOKEN/scripts/error-report" \
        -H "Content-Type: application/json" \
        -d "$(jq -n --arg s "$stage" --arg r "$report" \
              '{rootScript:"install.sh",rootVersion:"'"$SCRIPT_VERSION"'",stage:$s,reportText:$r}')" \
        || true
}

trap 'send_error "stage=$stage"' ERR

stage="download"
curl -fsSL "$BASE_URL/sub/$TOKEN/scripts/helper.sh/raw" -o /tmp/helper.sh
```

**Правило:** `trap ... ERR` в начале. `send_error` не должен сам падать (`|| true`).

**Правило:** если скачиваешь другой скрипт — читай `X-Pohr-Script-Version` через `curl -D -`.

---

## 5. Соглашения

### 5.1 Кодировки и line endings

| Файл | Кодировка при отдаче | Line endings |
|---|---|---|
| `.bat`, `.cmd` | CP866 | CRLF |
| `.ps1` | UTF-8 с BOM | CRLF |
| `.sh` | UTF-8 без BOM | LF |
| `.json` | UTF-8 | как есть |

**Правило:** сервер сам нормализует. Если пишешь `.ps1` и хочешь передать кириллицу в `Write-Host` — она попадёт корректно благодаря BOM.

### 5.2 Именование

- Файлы: `{platform}-{purpose}.{ext}` — `v2rayn-setup.ps1`, `happ-install.sh`.
- Переменные в `.ps1`: `$PohrScriptName`, `$PohrScriptVersion`, `$BaseUrl`, `$Token`, `$Stage`. Стиль — PascalCase для собственных, camelCase для локальных.
- Переменные в `.sh`: `SCREAMING_SNAKE_CASE` для констант, `snake_case` для остальных.

### 5.3 Логирование

**Правило:** логируй через `Write-Pohr*`, не через `Write-Host` напрямую. `Write-Pohr*` пишет и в консоль, и в `%LOCALAPPDATA%\Pohr\pohr.log`.

**Правило:** каждое логическое действие — одна строка лога с префиксом. Пример:
```
[INFO] Утилиты v1.3 загружены
[INFO] Найдено v2rayN: C:\v2ray\v2rayn
[INFO] Фрагментация включена (v2rayN 7.x)
[OK]   Подписка Pohr добавлена
```

### 5.4 Идемпотентность

**Правило:** скрипт должен уметь запускаться повторно без побочных эффектов. Любое изменение файла — сначала проверить, что оно нужно.

Пример — не «добавить подписку», а «удалить старую подписку Pohr и добавить новую»:
```powershell
$json.subItem = @($json.subItem | Where-Object { $_.remarks -ne "Pohr" })
$json.subItem += [PSCustomObject]@{ remarks = "Pohr"; ... }
```

Пример — не «включить фрагментацию», а «если фрагментация уже включена с теми же параметрами — не трогать»:
```powershell
if ($json.CoreBasicItem.EnableFragment -eq $true `
    -and $json.Fragment4RayItem.Packets -eq "tlshello") {
    Write-PohrDebug "Фрагментация уже настроена"
} else {
    # ... настройка ...
}
```

### 5.5 Транзакции при изменении файлов

**Правило:** если скрипт меняет файл, который не контролирует (`.json`, `.conf`, реестр), используй транзакцию:

```powershell
$pristine = New-PohrPristineBackup -FilePath $configPath -Tag "pohr"
$backup   = New-PohrTimestampedBackup -FilePath $configPath -Tag "pohr"
Start-PohrTransaction -Name "patch-$PohrScriptName" -BackupPath $backup

try {
    # ... изменение ...
    Complete-PohrTransaction
} catch {
    Copy-Item $backup $configPath -Force
    Complete-PohrTransaction
    throw
}
```

**Правило:** `Pristine` — один раз за всю жизнь файла. `Timestamped` — каждый запуск, с ротацией 5 последних.

---

## 6. Антипаттерны

❌ **Писать версию в исходник.** Не `# @version 1.5`, не `$SCRIPT_VERSION = "1.5"` руками. Только `{{__VERSION__}}`.

❌ **Хардкодить URL сервера.** Только `{{BASE_URL}}`, `{{TOKEN}}`, `{{SCRIPTS_BASE_URL}}`.

❌ **Писать свой HTTP-запрос.** Используй `Invoke-PohrHttp`.

❌ **Молча падать.** Всегда `try/catch` + `send error-report`. Всегда меняй `$Stage`.

❌ **Забыть sync в конце.** Сервер не узнает, что скрипт применился, и будет думать, что у пользователя ничего не стоит.

❌ **Трогать чужие подписки / конфиги.** Наша подписка — `remarks = "Pohr"`. Всё остальное — пользователя, не наше.

❌ **Делать `Write-Host` для логов.** Уйдёт в консоль, не в `pohr.log`. Используй `Write-Pohr*`.

❌ **Читать `{{__VERSION__}}` как строку внутри одинарных кавычек.** `'{{__VERSION__}}'` в PowerShell не подставляется — сервер заменит `{{__VERSION__}}` на `1.5` до запуска, так что это будет `'1.5'`. Но если ты пишешь `"$($PohrScriptVersion)"` — то переменная должна быть уже установлена, а её значение = `"1.5"`. Не путай серверную подстановку и PowerShell-интерполяцию.

❌ **Скачивать `.ps1` без чтения заголовка версии.** У пользователя будет `unknown` в sync, и в админке он не увидит разницу между старой и новой версией.

❌ **Использовать `curl | bash` как паттерн доставки.** Pohr раздаёт скрипты через `/raw`, не через пайпы. Это позволяет верифицировать содержимое и логировать скачивания.

---

## 7. Чек-лист нового скрипта

Перед тем как сохранять скрипт через `/admin/scripts/new` или в classpath:

- [ ] Имя файла следует `{platform}-{purpose}.{ext}`.
- [ ] В начале `.ps1` есть `param()` с `-RootName`, `-RootVersion`, `-Ps1Version`.
- [ ] `$ErrorActionPreference = "Stop"` (для `.ps1`) или `set -euo pipefail` (для `.sh`).
- [ ] Первый шаг — загрузка `pohr-utils.ps1` через `{{SCRIPTS_BASE_URL}}/pohr-utils.ps1/raw`, с чтением `X-Pohr-Script-Version`.
- [ ] Вся логика в `try/catch`.
- [ ] `$Stage` меняется перед каждым значимым шагом.
- [ ] Все изменения файлов идемпотентны.
- [ ] Изменения чужих файлов обёрнуты в транзакцию с `New-PohrPristineBackup` + `New-PohrTimestampedBackup`.
- [ ] В конце — `Send-PohrSync` с полным map'ом версий.
- [ ] В `catch` — `Send-PohrErrorReport` с полным отчётом (OS, PS, stage, exception).
- [ ] Логи через `Write-Pohr*`, не через `Write-Host`.
- [ ] Никаких хардкодов URL, токенов, версий.
- [ ] `{{__VERSION__}}` присутствует в шаблоне (для `.ps1`/`.bat`).
- [ ] Скрипт зарегистрирован в `/admin/scripts` с правильным `accessLevel` (`PUBLIC` для клиентских, `ADMIN` для серверных).
- [ ] Если это entrypoint — флаг `entrypoint = true`.
- [ ] Если ссылается на другие скрипты — их имена упомянуты в тексте (для автодетекта зависимостей).

---

## 8. Полный пример: `v2rayn-setup.ps1`

Ниже — минимально достаточный скелет, по которому можно писать любой клиентский скрипт.

```powershell
# Pohr: v2rayN setup for Windows.
# Парный .bat: v2rayn-setup.bat.

param(
    [Parameter(Mandatory)] [string] $RootName,
    [Parameter(Mandatory)] [string] $RootVersion,
    [string] $Ps1Version = "unknown"
)

$ErrorActionPreference = "Stop"

$PohrScriptName    = "v2rayn-setup.ps1"
$PohrScriptVersion = "{{__VERSION__}}"
$BaseUrl           = "{{BASE_URL}}"
$Token             = "{{TOKEN}}"
$ScriptsBaseUrl    = "{{SCRIPTS_BASE_URL}}"

$Stage = "init"
$UtilsVersion = "unknown"

try {

    # ---------- 1. Utils ----------
    $Stage = "load-utils"
    Write-Host "  Загружаю утилиты Pohr..." -ForegroundColor Cyan

    $utilsPath = Join-Path $env:TEMP "pohr-utils.ps1"
    $resp = Invoke-WebRequest -Uri "$ScriptsBaseUrl/pohr-utils.ps1/raw" `
                              -OutFile $utilsPath -UseBasicParsing -PassThru
    $UtilsVersion = $resp.Headers["X-Pohr-Script-Version"]
    . $utilsPath

    Write-PohrOk "Утилиты v$UtilsVersion загружены"

    # ---------- 2. Recovery ----------
    $Stage = "recovery"
    $pending = Get-PohrPendingTransaction
    if ($pending) {
        Write-PohrWarn "Незавершённая операция: $($pending.name)"
        if (Test-Path $pending.backup) {
            Copy-Item $pending.backup $pending.target -Force
            Write-PohrOk "Восстановлено из бэкапа"
        }
        Complete-PohrTransaction
    }

    # ---------- 3. Поиск клиента ----------
    $Stage = "find-client"
    # ... (функции поиска v2rayN) ...
    $v2rayDir = Get-V2rayDir
    if (-not $v2rayDir) {
        throw "v2rayN не найден. Установите его с https://github.com/2dust/v2rayN/releases"
    }
    Write-PohrOk "v2rayN найден: $v2rayDir"

    $configPath = Find-V2rayConfig $v2rayDir
    if (-not $configPath) {
        throw "guiNConfig.json не найден. Запустите v2rayN один раз и повторите."
    }

    # ---------- 4. Backup + transaction ----------
    $Stage = "backup"
    New-PohrPristineBackup -FilePath $configPath -Tag "pohr" | Out-Null
    $backup = New-PohrTimestampedBackup -FilePath $configPath -Tag "pohr"
    Start-PohrTransaction -Name "patch-$PohrScriptName" -BackupPath $backup -Target $configPath

    try {
        # ---------- 5. Остановка клиента ----------
        $Stage = "stop-client"
        Get-Process -Name "v2rayN" -ErrorAction SilentlyContinue | Stop-Process -Force
        Start-Sleep -Seconds 2

        # ---------- 6. Патч конфига ----------
        $Stage = "patch-config"
        $json = Read-PohrJson $configPath

        # subItem
        if (-not ($json.PSObject.Properties.Name -contains "subItem")) {
            $json | Add-Member -NotePropertyName "subItem" -NotePropertyValue @()
        }
        $json.subItem = @($json.subItem | Where-Object { $_.remarks -ne "Pohr" })
        $json.subItem += [PSCustomObject]@{
            id                 = [guid]::NewGuid().ToString()
            remarks            = "Pohr"
            url                = "{{SUBSCRIPTION_URL}}"
            enabled            = $true
            convertTarget      = "v2ray"
            autoUpdateInterval = 360
        }

        # fragment (v7)
        if ($json.PSObject.Properties.Name -contains "CoreBasicItem") {
            $json.CoreBasicItem.EnableFragment = $true
            $json | Add-Member -NotePropertyName "Fragment4RayItem" `
                    -NotePropertyValue ([PSCustomObject]@{
                        Packets  = "tlshello"
                        Lengths  = @("100-200")
                        Delays   = @("10-20")
                        MaxSplit = "0"
                    }) -Force
        }

        Write-PohrJson -Object $json -Path $configPath
        Write-PohrOk "Конфиг обновлён"

        Complete-PohrTransaction
    } catch {
        Write-PohrError "Ошибка патча, восстанавливаю из бэкапа"
        Copy-Item $backup $configPath -Force
        Complete-PohrTransaction
        throw
    }

    # ---------- 7. Запуск клиента ----------
    $Stage = "start-client"
    Start-Process -FilePath (Join-Path $v2rayDir "v2rayN.exe") -WorkingDirectory $v2rayDir

    # ---------- 8. Sync ----------
    $Stage = "sync"
    $versions = @{
        $RootName        = $RootVersion
        $PohrScriptName  = $PohrScriptVersion
        "pohr-utils.ps1" = $UtilsVersion
    }
    Send-PohrSync -BaseUrl $BaseUrl -Token $Token -RootName $RootName -Versions $versions

    Write-Host ""
    Write-PohrOk "Готово. Откройте v2rayN и обновите подписку ""Pohr"" (Ctrl+E)."
    exit 0
}
catch {
    $Stage = $Stage ?? "unknown"
    $report = @"
Script:    $PohrScriptName v$PohrScriptVersion
Root:      $RootName v$RootVersion
Stage:     $Stage
Exception: $($_.Exception.GetType().FullName)
Message:   $($_.Exception.Message)
Stack:     $($_.ScriptStackTrace)
OS:        $([System.Environment]::OSVersion)
PS:        $($PSVersionTable.PSVersion)
"@
    Write-PohrError "Ошибка на шаге '$Stage': $($_.Exception.Message)"

    if (Read-PohrConfirm "Произошла ошибка. Отправить отчёт разработчику?" -Default $true) {
        try {
            Send-PohrErrorReport -BaseUrl $BaseUrl -Token $Token `
                                 -RootName $RootName -RootVersion $RootVersion `
                                 -Stage $Stage -Report $report
        } catch {}
    }
    exit 1
}
```

Этот шаблон — эталон. Любой новый скрипт строится по нему: `param()` → utils → recovery → domain logic → sync, всё в `try/catch`.