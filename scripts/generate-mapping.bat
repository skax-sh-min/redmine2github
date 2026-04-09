@echo off
:: ============================================================
:: generate-mapping.bat -- Generate user-mapping.yml (Windows)
::
:: Usage:
::   scripts\generate-mapping.bat
::   scripts\generate-mapping.bat --output my-mapping.yml
::
:: Required env vars:
::   REDMINE_URL (required)
::   REDMINE_API_KEY or REDMINE_USERNAME + REDMINE_PASSWORD (required)
:: ============================================================

setlocal enabledelayedexpansion

:: UTF-8 console (prevents garbled output)
chcp 65001 > nul

set "SCRIPT_DIR=%~dp0"
set "JAR=%SCRIPT_DIR%..\build\libs\redmine2github.jar"

:: -- .env load (OS env vars take priority) ----------------------------
set "ENV_FILE=%SCRIPT_DIR%..\.env"
if exist "%ENV_FILE%" (
    for /f "usebackq tokens=1,* delims==" %%A in ("%ENV_FILE%") do (
        set "LINE=%%A"
        if not "!LINE:~0,1!"=="#" (
            if not "%%A"=="" (
                if not defined %%A set "%%A=%%B"
            )
        )
    )
)

:: -- JAR check --------------------------------------------------------
if not exist "%JAR%" (
    echo [ERROR] JAR not found: %JAR%
    echo   Build first: gradlew.bat shadowJar
    exit /b 1
)

:: -- Java check -------------------------------------------------------
where java >nul 2>&1
if errorlevel 1 (
    echo [ERROR] java not found. Install JDK 21 or later.
    exit /b 1
)

:: -- Env var check ----------------------------------------------------
if not defined REDMINE_URL (
    echo [ERROR] REDMINE_URL is not set.
    exit /b 1
)

:: -- Run --------------------------------------------------------------
echo ============================================
echo  Generate user mapping
echo  Redmine : %REDMINE_URL%
echo ============================================

java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -jar "%JAR%" generate-mapping %*
exit /b %errorlevel%
