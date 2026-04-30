@echo off
:: ============================================================
:: upload.bat -- Phase 2: local -> GitHub (Windows)
::
:: Usage:
::   scripts\upload.bat [options...]
::
:: Examples:
::   scripts\upload.bat                          :: full upload
::   scripts\upload.bat --only wiki              :: wiki only
::   scripts\upload.bat --only issues            :: issues only
::   scripts\upload.bat --only time-entries      :: time entries only
::   scripts\upload.bat --resume                 :: resume from checkpoint
::   scripts\upload.bat --retry-failed           :: retry failed items
::   scripts\upload.bat --all                    :: upload all projects under output\
::   scripts\upload.bat --all --only wiki        :: all projects, wiki only
::   scripts\upload.bat --all --skip foo,bar     :: skip specific projects
::
:: Prerequisite: run fetch.bat first so output\ files are ready.
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

set "LOG=%SCRIPT_DIR%..\migration.log"
echo. >> "%LOG%"
echo === [upload] %DATE% %TIME% === >> "%LOG%"
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar "%JAR%" upload %* 2>&1 | powershell -noprofile -Command "[Console]::InputEncoding=[Console]::OutputEncoding=[System.Text.UTF8Encoding]::new($false);$enc=[System.Text.UTF8Encoding]::new($false);$sw=[System.IO.StreamWriter]::new($env:LOG,$true,$enc);try{$input|%%{[Console]::WriteLine($_);$sw.WriteLine($_)}}finally{$sw.Close()}"
exit /b %errorlevel%
