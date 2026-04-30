@echo off
:: ============================================================
:: fetch.bat -- Phase 1: Redmine -> local (Windows)
::
:: Usage:
::   scripts\fetch.bat [options...]
::
:: Single project:
::   scripts\fetch.bat                          :: uses REDMINE_PROJECT env var
::   scripts\fetch.bat --project my-project     :: explicit project
::   scripts\fetch.bat --only wiki              :: wiki only
::   scripts\fetch.bat --resume                 :: resume from last checkpoint
::
:: All projects:
::   scripts\fetch.bat --all                    :: fetch all projects
::   scripts\fetch.bat --all --only wiki        :: all projects, wiki only
::   scripts\fetch.bat --all --skip foo,bar     :: skip some projects
::   scripts\fetch-all.bat                      :: alias for the above
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

set "LOG=%SCRIPT_DIR%..\migration.log"
echo. >> "%LOG%"
echo === [fetch] %DATE% %TIME% === >> "%LOG%"
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar "%JAR%" fetch %* 2>&1 | powershell -noprofile -Command "[Console]::InputEncoding=[Console]::OutputEncoding=[System.Text.UTF8Encoding]::new($false);$enc=[System.Text.UTF8Encoding]::new($false);$sw=[System.IO.StreamWriter]::new($env:LOG,$true,$enc);try{$input|%%{[Console]::WriteLine($_);$sw.WriteLine($_)}}finally{$sw.Close()}"
exit /b %errorlevel%
