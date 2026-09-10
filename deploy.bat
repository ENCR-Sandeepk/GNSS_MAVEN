@echo off
setlocal enabledelayedexpansion

REM ============================================================
REM  GNSS deploy helper (PuTTY pscp/plink - auto password)
REM  Transfers JAR / UI / gnss / server.c to the device.
REM  Run from anywhere; it cd's to its own folder (project root).
REM ============================================================

REM ---- Config (edit if needed) ----
set "USER=debian"
set "IP=192.168.10.100"
set "PW=beagle"
set "HOMEDIR=/home/debian"

set "JAR=target\GNSS_Rover_Base.jar"
set "UIDIR=gnss\ui"
set "INDEXHTML=gnss\index.html"
set "GNSSDIR=gnss"
set "SERVERC=server.c"

REM PuTTY tools - if not on PATH, set full paths, e.g.:
REM   set "PSCP=C:\Program Files\PuTTY\pscp.exe"
REM   set "PLINK=C:\Program Files\PuTTY\plink.exe"
set "PSCP=pscp"
set "PLINK=plink"

set "TARGET=%USER%@%IP%"

REM ---- Result tracking ----
set "OKLIST="
set "FAILLIST="

cd /d "%~dp0"

REM ---- Check PuTTY tools exist ----
where %PSCP% >nul 2>&1 || (echo ERROR: pscp not found. Install PuTTY or set PSCP to its full path. & goto end)
where %PLINK% >nul 2>&1 || (echo ERROR: plink not found. Install PuTTY or set PLINK to its full path. & goto end)

:menu
echo.
echo ============================================================
echo   Deploy GNSS to %TARGET%   (auto password)
echo ============================================================
echo   1. JAR only                 (target\GNSS_Rover_Base.jar)
echo   2. UI only                  (gnss\ui + gnss\index.html)
echo   3. JAR + UI
echo   4. JAR + UI + restart gnss.service
echo   5. FULL gnss folder         (WARNING: overwrites device config/logs)
echo   6. server.c                 (+ recompile on device)
echo   0. Exit
echo ============================================================
set "choice="
set /p "choice=Enter choice: "

if "%choice%"=="1" ( call :sendjar & goto done )
if "%choice%"=="2" ( call :sendui  & goto done )
if "%choice%"=="3" ( call :sendjar & call :sendui & goto done )
if "%choice%"=="4" ( call :sendjar & call :sendui & call :restart & goto done )
if "%choice%"=="5" ( call :sendgnss & goto done )
if "%choice%"=="6" ( call :sendserverc & goto done )
if "%choice%"=="0" goto end
echo Invalid choice, try again.
goto menu

:sendjar
echo.
if not exist "%JAR%" ( echo [JAR] NOT FOUND: %JAR%  ^(build first: mvn clean package^) & set "FAILLIST=!FAILLIST! JAR(not-found)" & exit /b 1 )
echo [JAR] %JAR%  -^>  %TARGET%:%HOMEDIR%/
%PSCP% -pw %PW% "%JAR%" %TARGET%:%HOMEDIR%/
if errorlevel 1 (echo [JAR] ***FAILED*** & set "FAILLIST=!FAILLIST! JAR") else (echo [JAR] OK & set "OKLIST=!OKLIST! JAR")
exit /b

:sendui
echo.
if not exist "%UIDIR%" ( echo [UI] NOT FOUND: %UIDIR% & set "FAILLIST=!FAILLIST! UI(not-found)" & exit /b 1 )
echo [UI] clean-replacing remote ui folder...
%PLINK% -pw %PW% %TARGET% "rm -rf %HOMEDIR%/gnss/ui"
echo [UI] %UIDIR%  -^>  %TARGET%:%HOMEDIR%/gnss/
%PSCP% -pw %PW% -r "%UIDIR%" %TARGET%:%HOMEDIR%/gnss/
if errorlevel 1 (echo [UI] ui folder ***FAILED*** & set "FAILLIST=!FAILLIST! UI-folder") else (echo [UI] ui folder OK ^(exact copy^) & set "OKLIST=!OKLIST! UI-folder")
if exist "%INDEXHTML%" (
    echo [UI] %INDEXHTML%  -^>  %TARGET%:%HOMEDIR%/gnss/
    %PSCP% -pw %PW% "%INDEXHTML%" %TARGET%:%HOMEDIR%/gnss/
    if errorlevel 1 (echo [UI] index.html ***FAILED*** & set "FAILLIST=!FAILLIST! index.html") else (echo [UI] index.html OK & set "OKLIST=!OKLIST! index.html")
)
echo [UI] normalizing permissions...
%PLINK% -pw %PW% %TARGET% "chmod -R u=rwX,go=rX %HOMEDIR%/gnss/ui; chown -R %USER%:%USER% %HOMEDIR%/gnss/ui"
exit /b

:sendgnss
echo.
echo  *** WARNING *** Full gnss folder OVERWRITES device config (settings) and logs.
set "ok="
set /p "ok=Type YES to continue: "
if /I not "%ok%"=="YES" ( echo Cancelled. & exit /b )
if not exist "%GNSSDIR%" ( echo [GNSS] NOT FOUND: %GNSSDIR% & set "FAILLIST=!FAILLIST! GNSS(not-found)" & exit /b 1 )
echo [GNSS] %GNSSDIR%  -^>  %TARGET%:%HOMEDIR%/
%PSCP% -pw %PW% -r "%GNSSDIR%" %TARGET%:%HOMEDIR%/
if errorlevel 1 (echo [GNSS] ***FAILED*** & set "FAILLIST=!FAILLIST! GNSS-folder" & exit /b 1) else (echo [GNSS] OK & set "OKLIST=!OKLIST! GNSS-folder")
echo [GNSS] normalizing permissions...
%PLINK% -pw %PW% %TARGET% "chmod -R u=rwX,go=rX %HOMEDIR%/gnss; chown -R %USER%:%USER% %HOMEDIR%/gnss"
exit /b

:sendserverc
echo.
if not exist "%SERVERC%" ( echo [SRV] NOT FOUND: %SERVERC% & set "FAILLIST=!FAILLIST! server.c(not-found)" & exit /b 1 )
echo [SRV] %SERVERC%  -^>  %TARGET%:%HOMEDIR%/
%PSCP% -pw %PW% "%SERVERC%" %TARGET%:%HOMEDIR%/
if errorlevel 1 (echo [SRV] scp ***FAILED*** & set "FAILLIST=!FAILLIST! server.c" & exit /b 1) else (echo [SRV] scp OK & set "OKLIST=!OKLIST! server.c")
set "rc="
set /p "rc=Recompile server.c on the device now? (Y/N): "
if /I "%rc%"=="Y" (
    echo [SRV] compiling: gcc server.c -o gnss_gui_server -pthread
    %PLINK% -pw %PW% %TARGET% "cd %HOMEDIR% && gcc server.c -o gnss_gui_server -pthread && echo COMPILE_OK || echo COMPILE_FAILED"
)
exit /b

:restart
echo.
echo [SVC] restarting gnss.service...
%PLINK% -pw %PW% %TARGET% "echo %PW% | sudo -S systemctl restart gnss.service"
if errorlevel 1 (echo [SVC] restart ***FAILED*** & set "FAILLIST=!FAILLIST! service-restart") else (echo [SVC] restarted & set "OKLIST=!OKLIST! service-restart")
exit /b

:done
echo.
echo ============================================================
echo   DEPLOY SUMMARY
echo ============================================================
if defined OKLIST (
    echo   [OK]     !OKLIST!
) else (
    echo   [OK]     ^(none^)
)
if defined FAILLIST (
    echo   [FAILED]!FAILLIST!
    echo ------------------------------------------------------------
    echo   RESULT: ONE OR MORE TRANSFERS FAILED - see [FAILED] above.
) else (
    echo ------------------------------------------------------------
    echo   RESULT: ALL TRANSFERS SUCCESSFUL.
)
echo ============================================================

:end
endlocal
echo.
pause
