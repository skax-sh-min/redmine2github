@echo off
:: ============================================================
:: fetch.bat -- Phase 1: Redmine -> local (Windows)
::
:: 사용법:
::   scripts\fetch.bat [옵션...]
::
:: 단일 프로젝트:
::   scripts\fetch.bat                          :: REDMINE_PROJECT 환경 변수 사용
::   scripts\fetch.bat --project my-project     :: 직접 지정
::   scripts\fetch.bat --only wiki              :: Wiki만
::   scripts\fetch.bat --resume                 :: 이전 중단 지점부터 재개
::
:: 전체 프로젝트:
::   scripts\fetch.bat --all                    :: 모든 프로젝트 수집
::   scripts\fetch.bat --all --only wiki        :: 모든 프로젝트, Wiki만
::   scripts\fetch.bat --all --skip foo,bar     :: 일부 제외
::   scripts\fetch-all.bat                      :: 위와 동일한 별칭
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
:: REDMINE_PROJECT: not required when --all or --project is used (validated by CLI)

:: -- Run --------------------------------------------------------------
echo ============================================
echo  [Phase 1] Redmine -^> Local fetch
echo  Redmine : %REDMINE_URL%
if defined REDMINE_PROJECTS (echo  Projects: %REDMINE_PROJECTS%) else if defined REDMINE_PROJECT (echo  Project : %REDMINE_PROJECT%)
if defined OUTPUT_DIR (echo  Output  : %OUTPUT_DIR%) else (echo  Output  : .\output)
echo ============================================

java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -jar "%JAR%" fetch %*
exit /b %errorlevel%
