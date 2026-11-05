@echo off
setlocal EnableDelayedExpansion
title LumenOS Boot check
cd /d "%~dp0"
echo.
echo   LumenOS Boot check: why the boot logo stays so long
echo   ===================================================
echo.
call :getadb || goto :noadb
set "ADB=%~dp0platform-tools\adb.exe"
set "IP=%~1"
if "%IP%"=="" set /p "IP=  Projector IP address, or type USB if it is connected by cable: "
if "%IP%"=="" goto :noconn
if /i "%IP%"=="usb" (set "SEL=-d") else (set "SEL=-s %IP%:5555")
if /i not "%IP%"=="usb" "%ADB%" connect %IP%:5555 >nul 2>&1
"%ADB%" %SEL% get-state 2>nul | find "device" >nul || goto :noconn
set "LOG=%~dp0boot-log.txt"
"%ADB%" %SEL% push "%~dp0files\bootcheck.sh" /data/local/tmp/bootcheck.sh >nul || goto :noconn
echo   Saving how the last start went...
"%ADB%" %SEL% shell sh /data/local/tmp/bootcheck.sh report > "%LOG%" 2>&1

set "APK=%~dp0files\Lumen.apk"
if exist "%~dp0Lumen.apk" set "APK=%~dp0Lumen.apk"
if exist "%APK%" (
  echo   Installing the Lumen next to this tool ^(8.3 waits for the boot logo to go before the intro^)...
  "%ADB%" %SEL% install -r "%APK%" >> "%LOG%" 2>&1
)

echo.
echo   The first LumenOS installer switched this projector's own launcher off. On some
echo   projectors that launcher is what clears the boot logo, so the logo stays for minutes.
choice /c YN /n /m "  Switch the projector's own launcher back on (Lumen stays your home screen)? [Y/N] "
if not errorlevel 2 (
  "%ADB%" %SEL% shell sh /data/local/tmp/bootcheck.sh launcher
  >> "%LOG%" echo === launcher switched back on ===
)

echo.
choice /c YN /n /m "  Restart the projector now and time the start? [Y/N] "
if errorlevel 2 goto :done
for /f %%T in ('powershell -NoProfile -Command "[DateTimeOffset]::Now.ToUnixTimeSeconds()"') do set "T0=%%T"
"%ADB%" %SEL% reboot
echo   Restarting. Watch the projector.
timeout /t 15 /nobreak >nul
set "TBOOT="
set /a N=0
:poll
if /i not "%IP%"=="usb" "%ADB%" connect %IP%:5555 >nul 2>&1
for /f "delims=" %%B in ('call "%ADB%" %SEL% shell getprop sys.boot_completed 2^>nul') do if "%%B"=="1" goto :booted
set /a N+=1
if !N! geq 160 goto :booted
timeout /t 5 /nobreak >nul
goto :poll
:booted
for /f %%T in ('powershell -NoProfile -Command "[DateTimeOffset]::Now.ToUnixTimeSeconds() - %T0%"') do set "TBOOT=%%T"
echo   Android reports it finished starting after about !TBOOT! seconds.
echo.
echo   Now press any key here THE MOMENT the boot logo disappears from the projector.
pause >nul
for /f %%T in ('powershell -NoProfile -Command "[DateTimeOffset]::Now.ToUnixTimeSeconds() - %T0%"') do set "TLOGO=%%T"
echo   The logo went after about !TLOGO! seconds.
>> "%LOG%" echo.
>> "%LOG%" echo === timed restart: Android finished at !TBOOT! s, logo gone at !TLOGO! s ===
"%ADB%" %SEL% push "%~dp0files\bootcheck.sh" /data/local/tmp/bootcheck.sh >nul 2>&1
"%ADB%" %SEL% shell sh /data/local/tmp/bootcheck.sh report >> "%LOG%" 2>&1

:done
echo.
echo   Saved boot-log.txt next to this file. Please send it over.
pause
exit /b 0

:noconn
echo.
echo   Could not reach the projector. Check the IP (or use the USB cable and type USB).
pause
exit /b 1

:noadb
echo   Could not download the adb tool. Check the internet connection and try again.
pause
exit /b 1

:getadb
if exist "%~dp0platform-tools\adb.exe" exit /b 0
echo   Downloading Android platform-tools (adb) from Google...
curl -L --fail -o "%~dp0platform-tools.zip" "https://dl.google.com/android/repository/platform-tools-latest-windows.zip" || exit /b 1
tar -xf "%~dp0platform-tools.zip" -C "%~dp0." || exit /b 1
del "%~dp0platform-tools.zip"
exit /b 0
