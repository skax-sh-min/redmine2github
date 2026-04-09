@echo off
:: ============================================================
:: migrate.bat -- fetch + upload pipeline (Windows)
::
:: Usage:
::   scripts\migrate.bat [options...]
::
:: Examples:
::   scripts\migrate.bat                          :: full migration
::   scripts\migrate.bat --only wiki              :: wiki only
::   scripts\migrate.bat --resume                 :: resume from checkpoint
::   scripts\migrate.bat --retry-failed           :: retry failed items
::
:: For step-by-step control, run fetch.bat and upload.bat separately.
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
if not defined REDMINE_PROJECT (
    echo [ERROR] REDMINE_PROJECT is not set.
    exit /b 1
)
if not defined GITHUB_TOKEN (
    echo [ERROR] GITHUB_TOKEN is not set.
    exit /b 1
)
if not defined GITHUB_REPO (
    echo [ERROR] GITHUB_REPO is not set.
    exit /b 1
)

:: -- Run --------------------------------------------------------------
echo ============================================
echo  Redmine -^> GitHub migration
echo  Redmine : %REDMINE_URL%/projects/%REDMINE_PROJECT%
echo  GitHub  : %GITHUB_REPO%
echo ============================================

java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -jar "%JAR%" migrate %*
exit /b %errorlevel%
