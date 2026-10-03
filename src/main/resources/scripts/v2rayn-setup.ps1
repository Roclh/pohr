$ErrorActionPreference = "Stop"
$SubscriptionUrl = "{{SUBSCRIPTION_URL}}"
$RulesUrl        = "{{RULES_URL}}"

Write-Host ""
Write-Host "  Поиск установки v2rayN..." -ForegroundColor Cyan

$SavedExeFile    = Join-Path $env:LOCALAPPDATA "Pohr\v2rayN-dir.txt"
$SavedConfigFile = Join-Path $env:LOCALAPPDATA "Pohr\v2rayN-config.txt"

function Test-V2rayDir($dir) {
    if (-not $dir) { return $false }
    return Test-Path (Join-Path $dir "v2rayN.exe")
}

# Ищет guiNConfig.json относительно папки с exe.
# v2rayN <= 6.x: <dir>\guiNConfig.json
# v2rayN >= 7.x: <dir>\guiConfigs\guiNConfig.json
function Find-V2rayConfig($dir) {
    if (-not $dir) { return $null }
    $candidates = @(
        (Join-Path $dir "guiNConfig.json"),
        (Join-Path $dir "guiConfigs\guiNConfig.json"),
        (Join-Path $dir "config\guiNConfig.json"),
        (Join-Path $dir "configs\guiNConfig.json")
    )
    foreach ($c in $candidates) {
        if (Test-Path $c) { return $c }
    }
    return $null
}

function Get-V2rayFromProcess {
    $p = Get-Process -Name "v2rayN" -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($p -and $p.Path) { return Split-Path $p.Path -Parent }
    return $null
}

function Get-V2rayFromRegistry {
    $roots = @(
        "HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall",
        "HKLM:\Software\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall",
        "HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall"
    )
    foreach ($root in $roots) {
        if (-not (Test-Path $root)) { continue }
        $found = $null
        Get-ChildItem $root -ErrorAction SilentlyContinue | ForEach-Object {
            if ($found) { return }
            $props = Get-ItemProperty $_.PSPath -ErrorAction SilentlyContinue
            if ($props.DisplayName -like "*v2rayN*") {
                if ($props.InstallLocation -and (Test-V2rayDir $props.InstallLocation)) {
                    $found = $props.InstallLocation
                    return
                }
                if ($props.DisplayIcon) {
                    $icon = $props.DisplayIcon.Trim('"') -replace ',\d+$',''
                    if (Test-Path $icon) {
                        $dir = Split-Path $icon -Parent
                        if (Test-V2rayDir $dir) { $found = $dir; return }
                    }
                }
            }
        }
        if ($found) { return $found }
    }
    return $null
}

function Get-V2rayFromStartMenu {
    $menuRoots = @(
        (Join-Path $env:APPDATA "Microsoft\Windows\Start Menu\Programs"),
        (Join-Path $env:ProgramData "Microsoft\Windows\Start Menu\Programs")
    )
    $shell = New-Object -ComObject WScript.Shell
    foreach ($root in $menuRoots) {
        if (-not (Test-Path $root)) { continue }
        $lnks = Get-ChildItem $root -Filter "*.lnk" -Recurse -ErrorAction SilentlyContinue |
                Where-Object { $_.Name -like "*v2ray*" }
        foreach ($lnk in $lnks) {
            try {
                $target = $shell.CreateShortcut($lnk.FullName).TargetPath
                if ($target -and (Split-Path $target -Leaf) -eq "v2rayN.exe") {
                    return Split-Path $target -Parent
                }
            } catch { }
        }
    }
    return $null
}

function Get-V2rayFromPath {
    $where = & where.exe v2rayN.exe 2>$null
    if ($LASTEXITCODE -eq 0 -and $where) {
        return Split-Path ($where | Select-Object -First 1) -Parent
    }
    return $null
}

function Get-V2rayFromCommonPaths {
    $candidates = @(
        "$env:USERPROFILE\Desktop\v2rayN",
        "$env:USERPROFILE\Desktop\v2rayN-windows-64",
        "$env:USERPROFILE\Downloads\v2rayN",
        "$env:USERPROFILE\Downloads\v2rayN-windows-64",
        "$env:USERPROFILE\Documents\v2rayN",
        "C:\v2ray\v2rayN-windows-64",
        "C:\v2ray\v2rayN",
        "C:\v2rayN",
        "C:\v2rayN\v2rayN-windows-64",
        "C:\Tools\v2rayN",
        "C:\Tools\v2rayN-windows-64",
        "C:\Program Files\v2rayN",
        "C:\Program Files (x86)\v2rayN",
        "$env:APPDATA\v2rayN",
        "$env:LOCALAPPDATA\v2rayN",
        (Get-Location).Path
    )
    foreach ($d in $candidates) {
        if (Test-V2rayDir $d) { return $d }
    }
    return $null
}

function Get-V2rayFromFilesystem {
    $roots = @()
    $roots += "$env:USERPROFILE"
    $roots += (Get-PSDrive -PSProvider FileSystem -ErrorAction SilentlyContinue |
               Where-Object { $_.Name -match '^[A-Z]$' } |
               ForEach-Object { "$($_.Name):\" })

    $skipNames = @('Windows', 'Program Files', 'Program Files (x86)', 'ProgramData',
                   'node_modules', '$Recycle.Bin', 'System Volume Information', 'AppData')

    foreach ($root in $roots) {
        if (-not (Test-Path $root)) { continue }
        Write-Host "    Сканирую $root (до 15 сек)..." -ForegroundColor DarkGray
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        $found = $null
        try {
            $found = Get-ChildItem -Path $root -Filter "v2rayN.exe" -Recurse -Depth 4 `
                     -File -Force -ErrorAction SilentlyContinue |
                     Where-Object {
                         $sw.Elapsed.TotalSeconds -lt 15 -and
                         ($_.FullName -split '\\').Where({ $_ -in $skipNames }).Count -eq 0
                     } |
                     Select-Object -First 1
        } catch { }
        if ($found) { return (Split-Path $found.FullName -Parent) }
    }
    return $null
}

function Ask-ForV2rayDir {
    Add-Type -AssemblyName System.Windows.Forms | Out-Null
    $dlg = New-Object System.Windows.Forms.FolderBrowserDialog
    $dlg.Description = "Выберите папку, где лежит v2rayN.exe (например, C:\v2ray\v2rayN-windows-64)"
    $dlg.ShowNewFolderButton = $false
    if ($dlg.ShowDialog() -ne [System.Windows.Forms.DialogResult]::OK) {
        return $null
    }
    if (Test-V2rayDir $dlg.SelectedPath) {
        return $dlg.SelectedPath
    }
    Write-Host "  В выбранной папке нет v2rayN.exe." -ForegroundColor Red
    return $null
}

function Save-Text($path, $value) {
    try {
        $parent = Split-Path $path -Parent
        if (-not (Test-Path $parent)) {
            New-Item -ItemType Directory -Path $parent -Force | Out-Null
        }
        Set-Content -Path $path -Value $value -Encoding UTF8
    } catch { }
}

function Load-Text($path) {
    if (-not (Test-Path $path)) { return $null }
    return (Get-Content $path -Raw).Trim()
}

# --- Основной поиск ---

$v2rayDir = $null

$savedDir = Load-Text $SavedExeFile
if ($savedDir -and (Test-V2rayDir $savedDir)) {
    $v2rayDir = $savedDir
    Write-Host "  Найдено в сохранённом пути: $v2rayDir" -ForegroundColor Green
}

if (-not $v2rayDir) {
    $v2rayDir = Get-V2rayFromProcess
    if ($v2rayDir) { Write-Host "  Найдено по процессу: $v2rayDir" -ForegroundColor Green }
}
if (-not $v2rayDir) {
    $v2rayDir = Get-V2rayFromRegistry
    if ($v2rayDir) { Write-Host "  Найдено в реестре: $v2rayDir" -ForegroundColor Green }
}
if (-not $v2rayDir) {
    $v2rayDir = Get-V2rayFromStartMenu
    if ($v2rayDir) { Write-Host "  Найдено через Start Menu: $v2rayDir" -ForegroundColor Green }
}
if (-not $v2rayDir) {
    $v2rayDir = Get-V2rayFromPath
    if ($v2rayDir) { Write-Host "  Найдено в PATH: $v2rayDir" -ForegroundColor Green }
}
if (-not $v2rayDir) {
    $v2rayDir = Get-V2rayFromCommonPaths
    if ($v2rayDir) { Write-Host "  Найдено в стандартных путях: $v2rayDir" -ForegroundColor Green }
}
if (-not $v2rayDir) {
    Write-Host "  Автопоиск не дал результата, запускаю сканирование файловой системы..."
    $v2rayDir = Get-V2rayFromFilesystem
    if ($v2rayDir) { Write-Host "  Найдено сканированием: $v2rayDir" -ForegroundColor Green }
}
if (-not $v2rayDir) {
    Write-Host ""
    Write-Host "  Автопоиск не нашёл v2rayN. Выберите папку вручную." -ForegroundColor Yellow
    $v2rayDir = Ask-ForV2rayDir
}
if (-not $v2rayDir) {
    Write-Host ""
    Write-Host "  ОШИБКА: папка с v2rayN не указана." -ForegroundColor Red
    Write-Host "  Скачайте v2rayN: https://github.com/2dust/v2rayN/releases" -ForegroundColor Yellow
    exit 1
}

Save-Text $SavedExeFile $v2rayDir

# --- Поиск guiNConfig.json ---

$configPath = $null

$savedCfg = Load-Text $SavedConfigFile
if ($savedCfg -and (Test-Path $savedCfg)) {
    $configPath = $savedCfg
    Write-Host "  Конфиг (сохранённый путь): $configPath" -ForegroundColor Green
}

if (-not $configPath) {
    $configPath = Find-V2rayConfig $v2rayDir
    if ($configPath) { Write-Host "  Конфиг найден: $configPath" -ForegroundColor Green }
}

if (-not $configPath) {
    Write-Host ""
    Write-Host "  ОШИБКА: guiNConfig.json не найден в $v2rayDir" -ForegroundColor Red
    Write-Host "  Ожидаемые пути:" -ForegroundColor Yellow
    Write-Host "    $v2rayDir\guiNConfig.json"
    Write-Host "    $v2rayDir\guiConfigs\guiNConfig.json"
    Write-Host "  Запустите v2rayN хотя бы один раз, закройте и повторите." -ForegroundColor Yellow
    exit 1
}

Save-Text $SavedConfigFile $configPath

# --- Стоп v2rayN ---

$proc = Get-Process -Name "v2rayN" -ErrorAction SilentlyContinue
if ($proc) {
    Write-Host "  Останавливаю v2rayN..."
    $proc | Stop-Process -Force
    Start-Sleep -Seconds 2
}

# --- Бэкап ---

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$backup = "$configPath.pohr-$stamp.bak"
Copy-Item $configPath $backup
Write-Host "  Бэкап: $backup" -ForegroundColor Green

# --- Патч ---

try {
    $json = Get-Content $configPath -Raw -Encoding UTF8 | ConvertFrom-Json
} catch {
    Write-Host "  ОШИБКА: не удалось прочитать $configPath" -ForegroundColor Red
    Write-Host "  $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}

if (-not ($json.PSObject.Properties.Name -contains "subItem")) {
    $json | Add-Member -MemberType NoteProperty -Name "subItem" -Value @()
}
$json.subItem = @($json.subItem | Where-Object { $_.remarks -ne "Pohr" })
$json.subItem += [PSCustomObject]@{
    id                 = [guid]::NewGuid().ToString()
    remarks            = "Pohr"
    url                = $SubscriptionUrl
    moreUrl            = ""
    enabled            = $true
    userAgent          = ""
    convertTarget      = "v2ray"
    autoUpdateInterval = 360
    preSocksPort       = 0
    filter             = ""
    updateTime         = 0
}

# --- Fragment ---
# v2rayN 7.x: CoreBasicItem.EnableFragment + Fragment4RayItem
# v2rayN 6.x: fragmentItem (старый формат, оставлен для совместимости)

$isV7 = $json.PSObject.Properties.Name -contains "CoreBasicItem"

if ($isV7) {
    $json.CoreBasicItem.EnableFragment = $true

    if (-not ($json.PSObject.Properties.Name -contains "Fragment4RayItem")) {
        $json | Add-Member -MemberType NoteProperty -Name "Fragment4RayItem" -Value ([PSCustomObject]@{})
    }
    $json.Fragment4RayItem = [PSCustomObject]@{
        Packets  = "tlshello"
        Lengths  = @("100-200")
        Delays   = @("10-20")
        MaxSplit = "0"
        Length   = $null
        Interval = $null
    }

    # Убираем артефакт от старых версий нашего скрипта
    if ($json.PSObject.Properties.Name -contains "fragmentItem") {
        $json.PSObject.Properties.Remove("fragmentItem")
    }
    Write-Host "  Фрагмент включён (v2rayN 7.x: CoreBasicItem.EnableFragment)" -ForegroundColor Green
} else {
    # Fallback для v2rayN <= 6.x
    if (-not ($json.PSObject.Properties.Name -contains "fragmentItem")) {
        $json | Add-Member -MemberType NoteProperty -Name "fragmentItem" -Value ([PSCustomObject]@{})
    }
    $json.fragmentItem = [PSCustomObject]@{
        enabled  = $true
        packets  = "tlshello"
        length   = "100-200"
        interval = "10-20"
    }
    Write-Host "  Фрагмент включён (v2rayN 6.x: fragmentItem)" -ForegroundColor Green
}
try {
    $json | ConvertTo-Json -Depth 100 | Set-Content $configPath -Encoding UTF8
} catch {
    Write-Host "  ОШИБКА при записи конфига: $($_.Exception.Message)" -ForegroundColor Red
    Write-Host "  Восстанавливаю бэкап..." -ForegroundColor Yellow
    Copy-Item $backup $configPath -Force
    exit 1
}
Write-Host "  Конфиг обновлён: $configPath" -ForegroundColor Green

# --- Запуск ---

$exe = Join-Path $v2rayDir "v2rayN.exe"
Write-Host "  Запускаю v2rayN..."
Start-Process -FilePath $exe -WorkingDirectory $v2rayDir

Write-Host ""
Write-Host "  Готово!" -ForegroundColor Green
Write-Host "  Откройте v2rayN, найдите подписку ""Pohr"" и обновите её (Ctrl+E)."
exit 0