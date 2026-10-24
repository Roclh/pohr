#requires -Version 5.1
# Pohr MCP setup for DeepSeek Harness
# Generated at {{GENERATED_AT}}
# Pohr UI: {{POHR_URL}}
#
# Что делает скрипт:
#   1. добавляет MCP-сервер pohr в ~/.dsh/profiles/<profile>/cordis.patch.yml;
#   2. добавляет правила работы со скриптами в ~/.dsh/AGENTS.md;
#   3. делает .bak-копии обоих файлов.
#
# Обычно запускается через .bat-обёртку:
#   pohr-dsh-mcp-setup.bat

[CmdletBinding()]
param(
    [string]$DshHome = $(if ($env:POHR_DSH_HOME) { $env:POHR_DSH_HOME } elseif ($env:DSH_HOME) { $env:DSH_HOME } else { Join-Path $env:USERPROFILE '.dsh' }),
    [string]$Profile = 'desktop',
    [switch]$NoPause
)

$ErrorActionPreference = 'Stop'
if ($env:POHR_NO_PAUSE -eq '1') { $NoPause = $true }

trap {
    Write-Host ""
    Write-Host "[pohr-mcp] ОШИБКА: $_" -ForegroundColor Red
    if (-not $NoPause) {
        [void](Read-Host "Нажмите Enter, чтобы закрыть окно")
    }
    exit 1
}

$McpUrl   = '{{MCP_URL}}'
$Token    = '{{TOKEN}}'
$RulesB64 = '{{RULES_B64}}'
$script:Stamp = Get-Date -Format 'yyyyMMdd-HHmmss'

function Write-Step {
    param([string]$Message)
    Write-Host "[pohr-mcp] $Message" -ForegroundColor Cyan
}

function Write-Ok {
    param([string]$Message)
    Write-Host "[pohr-mcp] $Message" -ForegroundColor Green
}

function Write-Warn {
    param([string]$Message)
    Write-Host "[pohr-mcp] $Message" -ForegroundColor Yellow
}

function Read-Text {
    param([string]$Path)
    if (Test-Path -LiteralPath $Path) {
        return [IO.File]::ReadAllText($Path)
    }
    return ''
}

function Write-Text {
    param([string]$Path, [string]$Text)
    $dir = Split-Path -Parent $Path
    if ($dir -and -not (Test-Path -LiteralPath $dir)) {
        New-Item -ItemType Directory -Path $dir -Force | Out-Null
    }
    $encoding = New-Object System.Text.UTF8Encoding($false)
    [IO.File]::WriteAllText($Path, $Text, $encoding)
}

function Backup-File {
    param([string]$Path)
    if (Test-Path -LiteralPath $Path) {
        $bak = "$Path.bak-$script:Stamp"
        Copy-Item -LiteralPath $Path -Destination $bak -Force
        Write-Step "Backup: $bak"
    }
}

function Remove-MarkedBlock {
    param([string]$Text, [string]$Begin, [string]$End)
    if ([string]::IsNullOrEmpty($Text)) {
        return ''
    }
    $pattern = '(?s)' + [regex]::Escape($Begin) + '.*?' + [regex]::Escape($End)
    return [regex]::Replace($Text, $pattern, '').TrimEnd()
}

if ($McpUrl -notmatch '^https?://') {
    throw "Некорректный MCP URL: $McpUrl"
}
if (-not $Token.StartsWith('pohr_')) {
    throw "Похоже, в установщик не подставлен MCP-токен. Скачайте установщик заново на /admin/mcp."
}

if (-not (Test-Path -LiteralPath $DshHome)) {
    throw "Не найден каталог DeepSeek Harness: $DshHome. Запустите DSH хотя бы один раз."
}

$profilesRoot = Join-Path $DshHome 'profiles'
if (-not (Test-Path -LiteralPath $profilesRoot)) {
    throw "Не найден каталог профилей DSH: $profilesRoot"
}

$profileDir = Join-Path $profilesRoot $Profile
if (-not (Test-Path -LiteralPath $profileDir)) {
    $candidate = Get-ChildItem -LiteralPath $profilesRoot -Directory | Select-Object -First 1
    if ($null -eq $candidate) {
        throw "В $profilesRoot нет ни одного профиля DSH"
    }
    $profileDir = $candidate.FullName
    Write-Warn "Профиль '$Profile' не найден, использую '$($candidate.Name)'"
}

$patchPath  = Join-Path $profileDir 'cordis.patch.yml'
$agentsPath = Join-Path $DshHome 'AGENTS.md'

Write-Step "DSH home: $DshHome"
Write-Step "Profile:  $profileDir"
Write-Step "MCP:      $McpUrl"

# ---------------------------------------------------------------------------
# 1. cordis.patch.yml
# ---------------------------------------------------------------------------

$patchBegin = '# >>> pohr-mcp >>>'
$patchEnd   = '# <<< pohr-mcp <<<'

$patchText = Read-Text $patchPath

if ($patchText -match '(?m)^\s*-\s*id:\s*mcp-pohr\s*$' -and
    $patchText -notmatch [regex]::Escape($patchBegin)) {
    throw "В $patchPath уже есть mcp-pohr без маркеров Pohr. Удалите этот блок вручную и запустите установщик снова."
}

$patchText = Remove-MarkedBlock $patchText $patchBegin $patchEnd
if ($patchText.Trim() -eq '[]') {
    $patchText = ''
}

$patchBlock = @"
$patchBegin
- insert:
    - id: mcp-pohr
      name: "@deepseek-ai/dsh-mcp-client"
      config:
        serverName: pohr
        transport: streamable-http
        url: "$McpUrl"
        headers:
          Authorization: "Bearer $Token"
        toolCallTimeoutMs: 120000
$patchEnd
"@

if ([string]::IsNullOrWhiteSpace($patchText)) {
    $newPatch = $patchBlock.TrimStart() + "`n"
} else {
    $newPatch = $patchText.TrimEnd() + "`n`n" + $patchBlock.TrimStart() + "`n"
}

Backup-File $patchPath
Write-Text $patchPath $newPatch
Write-Ok "MCP-сервер pohr добавлен в $patchPath"

# ---------------------------------------------------------------------------
# 2. AGENTS.md
# ---------------------------------------------------------------------------

$agentsBegin = '<!-- pohr-mcp:begin -->'
$agentsEnd   = '<!-- pohr-mcp:end -->'

$rules = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($RulesB64))
if ([string]::IsNullOrWhiteSpace($rules)) {
    throw "Пустые правила агента — установщик повреждён"
}

$agentsText = Read-Text $agentsPath
$agentsText = Remove-MarkedBlock $agentsText $agentsBegin $agentsEnd

$section = $agentsBegin + "`n" + $rules.TrimEnd() + "`n" + $agentsEnd
if ([string]::IsNullOrWhiteSpace($agentsText)) {
    $newAgents = $section + "`n"
} else {
    $newAgents = $agentsText.TrimEnd() + "`n`n" + $section + "`n"
}

Backup-File $agentsPath
Write-Text $agentsPath $newAgents
Write-Ok "Правила Pohr добавлены в $agentsPath"

# ---------------------------------------------------------------------------
# 3. Проверка
# ---------------------------------------------------------------------------

$writtenPatch = Read-Text $patchPath
if ($writtenPatch -notmatch [regex]::Escape($patchBegin)) {
    throw "Не удалось записать $patchPath"
}
$writtenAgents = Read-Text $agentsPath
if ($writtenAgents -notmatch [regex]::Escape($agentsBegin)) {
    throw "Не удалось записать $agentsPath"
}

Write-Host ""
Write-Ok "Готово: MCP Pohr подключён к DeepSeek Harness."
Write-Host "  Endpoint: $McpUrl"
Write-Host "  Config:   $patchPath"
Write-Host "  Rules:    $agentsPath"
Write-Host ""
Write-Warn "Перезапустите DeepSeek Harness, чтобы он подхватил MCP-сервер и правила."
Write-Warn "Токен хранится в cordis.patch.yml — не коммитьте этот файл и не передавайте его третьим лицам."

if (-not $NoPause) {
    Write-Host ""
    [void](Read-Host "Нажмите Enter, чтобы закрыть окно")
}
