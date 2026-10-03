@echo off
chcp 866 >nul
setlocal
title Pohr - v2rayN Setup

echo.
echo  ==========================================
echo   Pohr: настройка v2rayN
echo  ==========================================
echo.

set "PS1_URL={{PS1_URL}}"
set "TEMP_PS1=%TEMP%\pohr-v2rayn-setup.ps1"

echo Скачиваю скрипт настройки...
powershell -NoProfile -ExecutionPolicy Bypass -Command "[Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12; try { Invoke-WebRequest -Uri '%PS1_URL%' -OutFile '%TEMP_PS1%' -UseBasicParsing; exit 0 } catch { Write-Host $_.Exception.Message -ForegroundColor Red; exit 1 }"

if errorlevel 1 (
    echo.
    echo ОШИБКА: не удалось скачать %PS1_URL%
    pause
    exit /b 1
)

echo Запускаю установщик...
powershell -NoProfile -ExecutionPolicy Bypass -File "%TEMP_PS1%"
set "EXITCODE=%errorlevel%"

del "%TEMP_PS1%" 2>nul

echo.
if %EXITCODE% equ 0 (
    echo  Готово.
) else (
    echo  Установка завершилась с ошибкой.
)
pause >nul
endlocal