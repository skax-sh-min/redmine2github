@echo off
:: ============================================================
:: upload.bat -- Phase 2: local -> GitHub (Windows)
::
:: 사용법:
::   scripts\upload.bat [옵션...]
::
:: 예시:
::   scripts\upload.bat                          :: 전체 업로드
::   scripts\upload.bat --only wiki              :: Wiki만
::   scripts\upload.bat --only issues            :: 일감만
::   scripts\upload.bat --only time-entries      :: 작업 내역만
::   scripts\upload.bat --resume                 :: 이전 중단 지점부터 재개
::   scripts\upload.bat --retry-failed           :: 실패 항목 재처리
::
:: 사전 조건: fetch.bat를 먼저 실행하여 output\ 파일이 준비되어 있어야 합니다.
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
if not defined GITHUB_TOKEN (
    echo [ERROR] GITHUB_TOKEN is not set.
    exit /b 1
)
if not defined GITHUB_REPO (
    echo [ERROR] GITHUB_REPO is not set.
    exit /b 1
)

:: -- output/ dir check ------------------------------------------------
if defined OUTPUT_DIR (
    set "OUTPUT_CHECK=%OUTPUT_DIR%"
) else (
    set "OUTPUT_CHECK=output"
)
if not exist "%OUTPUT_CHECK%" (
    echo [ERROR] Output directory not found: %OUTPUT_CHECK%
    echo   Run fetch first: scripts\fetch.bat
    exit /b 1
)

:: -- Run --------------------------------------------------------------
echo ============================================
echo  [Phase 2] Local -^> GitHub upload
echo  GitHub: %GITHUB_REPO%
if defined GITHUB_UPLOAD_METHOD (echo  Method: %GITHUB_UPLOAD_METHOD%) else (echo  Method: API)
echo ============================================

java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -jar "%JAR%" upload %*
exit /b %errorlevel%
