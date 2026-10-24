@echo off
setlocal EnableExtensions
title Pohr MCP setup
rem Generated at {{GENERATED_AT}} for {{POHR_URL}}

set "POHR_SELF=%~f0"
set "POHR_USERPROFILE=%USERPROFILE%"
set "POHR_PROFILE_FILE=%PUBLIC%\pohr-dsh-profile.txt"
if not defined PUBLIC set "POHR_PROFILE_FILE=%TEMP%\pohr-dsh-profile.txt"
set "POHR_TMP_B64=%TEMP%\pohr-dsh-mcp-setup.b64"
set "POHR_TMP_PS=%TEMP%\pohr-dsh-mcp-setup.ps1"
set "POHR_NO_PAUSE=0"
if /I "%~1"=="-NoPause" set "POHR_NO_PAUSE=1"
if not defined POHR_ELEVATED set "POHR_ELEVATED=0"

rem --- UAC: записи в профиль права админа не нужны, но установщик
rem --- запрашивает их по требованию. Для автотестов можно выставить
rem --- POHR_SKIP_ELEVATION=1.
if /I not "%POHR_SKIP_ELEVATION%"=="1" (
    net session >nul 2>&1
    if errorlevel 1 (
        echo [pohr-mcp] Requesting administrator rights...
        set "POHR_ELEVATED=1"
        powershell -NoProfile -ExecutionPolicy Bypass -Command "[IO.File]::WriteAllText($env:POHR_PROFILE_FILE, $env:USERPROFILE)"
        if "%POHR_NO_PAUSE%"=="1" (
            powershell -NoProfile -ExecutionPolicy Bypass -Command "Start-Process -FilePath $env:POHR_SELF -ArgumentList '-NoPause' -Verb RunAs"
        ) else (
            powershell -NoProfile -ExecutionPolicy Bypass -Command "Start-Process -FilePath $env:POHR_SELF -Verb RunAs"
        )
        if errorlevel 1 (
            echo [pohr-mcp] ERROR: administrator rights were not granted.
            if "%POHR_NO_PAUSE%"=="0" pause
        )
        exit /b
    )
)

rem --- После UAC читаем профиль исходного пользователя, а не админа.
if "%POHR_ELEVATED%"=="1" (
    if exist "%POHR_PROFILE_FILE%" (
        for /f "usebackq delims=" %%A in ("%POHR_PROFILE_FILE%") do set "POHR_USERPROFILE=%%A"
        del "%POHR_PROFILE_FILE%" >nul 2>&1
    )
)
set "POHR_DSH_HOME=%POHR_USERPROFILE%\.dsh"

rem --- Распаковываем встроенный .ps1 (Base64 → certutil), чтобы не упираться
rem --- в лимит длины командной строки cmd.exe.
{{B64_LINES}}
certutil -decode "%POHR_TMP_B64%" "%POHR_TMP_PS%" >nul 2>&1
if errorlevel 1 (
    echo [pohr-mcp] ERROR: failed to decode installer payload.
    del "%POHR_TMP_B64%" >nul 2>&1
    if "%POHR_NO_PAUSE%"=="0" pause
    exit /b 1
)
del "%POHR_TMP_B64%" >nul 2>&1

powershell -NoProfile -ExecutionPolicy Bypass -File "%POHR_TMP_PS%" -DshHome "%POHR_DSH_HOME%" -NoPause
set "POHR_RC=%ERRORLEVEL%"
del "%POHR_TMP_PS%" >nul 2>&1

if not "%POHR_RC%"=="0" (
    echo.
    echo [pohr-mcp] Installer failed with exit code %POHR_RC%.
)
echo.
if "%POHR_NO_PAUSE%"=="0" pause
exit /b %POHR_RC%
