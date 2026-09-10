@echo off
setlocal enabledelayedexpansion

REM ============================================================
REM  H3 - Journal cap installer (PuTTY pscp/plink, auto password)
REM  Installs the systemd-journald drop-in on a board:
REM    SystemMaxUse=80M  +  MaxRetentionSec=7day
REM  then restarts journald and vacuums the existing journal to 80M.
REM  Use on field boards that will NOT be re-imaged. (New boards
REM  inherit this automatically from the golden SD image.)
REM  Run from anywhere; it cd's to its own folder (project root).
REM ============================================================

REM ---- Config (edit if needed) ----
set "USER=debian"
set "IP=192.168.10.100"
set "PW=beagle"
set "HOMEDIR=/home/debian"

set "JOURNALD_DROPIN=board_config\journald.conf.d\99-gnss.conf"

REM PuTTY tools - if not on PATH, set full paths, e.g.:
REM   set "PSCP=C:\Program Files\PuTTY\pscp.exe"
REM   set "PLINK=C:\Program Files\PuTTY\plink.exe"
set "PSCP=pscp"
set "PLINK=plink"

set "TARGET=%USER%@%IP%"

cd /d "%~dp0"

REM ---- Check PuTTY tools exist ----
where %PSCP% >nul 2>&1 || (echo ERROR: pscp not found. Install PuTTY or set PSCP to its full path. & goto end)
where %PLINK% >nul 2>&1 || (echo ERROR: plink not found. Install PuTTY or set PLINK to its full path. & goto end)

echo.
echo ============================================================
echo   Install journal cap (H3) on %TARGET%
echo   80M / 7day  +  vacuum existing journal to 80M
echo ============================================================

if not exist "%JOURNALD_DROPIN%" ( echo [JNL] NOT FOUND: %JOURNALD_DROPIN% & goto done )

echo [JNL] %JOURNALD_DROPIN%  -^>  %TARGET%:%HOMEDIR%/
%PSCP% -pw %PW% "%JOURNALD_DROPIN%" %TARGET%:%HOMEDIR%/99-gnss.conf
if errorlevel 1 ( echo [JNL] scp ***FAILED*** & goto done )

echo [JNL] installing drop-in, restarting journald, vacuuming to 80M...
%PLINK% -pw %PW% %TARGET% "echo %PW% | sudo -S bash -c 'mkdir -p /etc/systemd/journald.conf.d && cp /home/debian/99-gnss.conf /etc/systemd/journald.conf.d/99-gnss.conf && systemctl restart systemd-journald && journalctl --vacuum-size=80M'"
if errorlevel 1 ( echo [JNL] install ***FAILED*** & goto done )

echo.
echo [JNL] OK - journal cap installed and vacuumed.
echo [JNL] current usage:
%PLINK% -pw %PW% %TARGET% "journalctl --disk-usage"

:done
echo.
echo ------------------------------------------------------------
echo  Finished.
echo ------------------------------------------------------------

:end
endlocal
echo.
pause
