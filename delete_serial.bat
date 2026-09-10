@echo off
setlocal enabledelayedexpansion

REM ============================================================
REM  Delete the serial-number file on a BeaglePlay board
REM  (PuTTY plink, auto password).
REM  File: /home/debian/gnss/.sys_cal.dat  (hidden, write-once locked)
REM  Deleting it lets you set a NEW serial number from /admin.
REM  Run from anywhere; it cd's to its own folder.
REM ============================================================

REM ---- Config (edit if needed) ----
set "USER=debian"
set "IP=192.168.10.100"
set "PW=beagle"
set "HOMEDIR=/home/debian"
set "SERIALFILE=%HOMEDIR%/gnss/.sys_cal.dat"

REM PuTTY tool - if not on PATH, set full path, e.g.:
REM   set "PLINK=C:\Program Files\PuTTY\plink.exe"
set "PLINK=plink"

set "TARGET=%USER%@%IP%"

cd /d "%~dp0"

where %PLINK% >nul 2>&1 || (echo ERROR: plink not found. Install PuTTY or set PLINK to its full path. & goto end)

echo.
echo ============================================================
echo   Delete serial file on %TARGET%
echo   %SERIALFILE%
echo ============================================================
echo.
echo Current serial-related file(s) on the board:
%PLINK% -pw %PW% %TARGET% "ls -la %SERIALFILE% 2>/dev/null; ls -la %HOMEDIR%/gnss/ | grep -i sys 2>/dev/null; echo ---"

echo.
echo This will PERMANENTLY delete the serial file so a new serial
echo number can be set. This cannot be undone (you must re-enter
echo the serial via the /admin page afterwards).
echo.
set "ok="
set /p "ok=Type YES to delete: "
if /I not "%ok%"=="YES" ( echo Cancelled. & goto end )

echo.
echo [DEL] deleting %SERIALFILE% ...
%PLINK% -pw %PW% %TARGET% "echo %PW% | sudo -S rm -f %SERIALFILE%"

echo [DEL] verifying...
%PLINK% -pw %PW% %TARGET% "if [ -f %SERIALFILE% ]; then echo STILL_EXISTS; else echo DELETED_OK; fi"

echo.
echo Done. If it printed DELETED_OK, open the /admin page and set the new serial.

:end
endlocal
echo.
pause
