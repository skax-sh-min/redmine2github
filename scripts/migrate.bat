@echo off
:: ============================================================
:: migrate.bat -- fetch + upload pipeline (Windows)
::
:: 사용법:
::   scripts\migrate.bat [옵션...]
::
:: 예시:
::   scripts\migrate.bat                          :: 전체 마이그레이션
::   scripts\migrate.bat --only wiki              :: Wiki만
::   scripts\migrate.bat --resume                 :: 이전 중단 지점부터 재개
::   scripts\migrate.bat --retry-failed           :: 실패 항목 재처리
::
:: 단계별 실행이 필요하면 fetch.bat / upload.bat를 개별 실행하세요.
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

java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -jar "%JAR%" migrate-all %*
exit /b %errorlevel%
