@echo off
setlocal
title LumenOS for HY300
cd /d "%~dp0"
echo.
echo   LumenOS for the HY300 projector
echo   ===============================
echo.
call :getadb || goto :fail
set "IP=%~1"
if "%IP%"=="" set /p "IP=  Projector IP address, or type USB if it is connected by cable: "
if "%IP%"=="" goto :fail
set "ADB=%~dp0platform-tools\adb.exe"
set "DEV=%IP%:5555"
if /i "%IP%"=="usb" (set "SEL=-d") else (set "SEL=-s %IP%:5555")
if /i not "%IP%"=="usb" "%ADB%" disconnect "%DEV%" >nul 2>&1
if /i not "%IP%"=="usb" "%ADB%" connect "%DEV%"
if /i "%IP%"=="usb" "%ADB%" devices >nul 2>&1
echo.
echo   If the projector asks "Allow USB debugging?" or "Allow network debugging?", tick Always allow and press OK.
echo   Then press any key here.
pause >nul
if /i not "%IP%"=="usb" "%ADB%" connect "%DEV%" >nul 2>&1
"%ADB%" %SEL% get-state 2>nul | find "device" >nul || goto :noconn
echo.
echo   Installing Lumen...
"%ADB%" %SEL% install -r "%~dp0files\Lumen.apk"
"%ADB%" %SEL% shell pm path com.lumen.launcher | find "package:" >nul || goto :fail
"%ADB%" %SEL% push "%~dp0files\lumenos.sh" /data/local/tmp/lumenos.sh >nul || goto :fail
"%ADB%" %SEL% push "%~dp0files\lumenos-undo.sh" /data/local/tmp/lumenos-undo.sh >nul
"%ADB%" %SEL% shell sh /data/local/tmp/lumenos.sh || goto :fail
if not exist "%~dp0backup" mkdir "%~dp0backup"
"%ADB%" %SEL% pull /data/local/tmp/lumenos "%~dp0backup" >nul 2>&1
"%ADB%" %SEL% reboot
echo.
echo   All done. After the restart, Lumen plays its intro and is your home screen.
echo   A copy of the backup is in the backup folder next to this file.
echo.
pause
exit /b 0

:getadb
if exist "%~dp0platform-tools\adb.exe" exit /b 0
echo   Downloading Android platform-tools (adb) from Google...
curl -L --fail -o "%~dp0platform-tools.zip" "https://dl.google.com/android/repository/platform-tools-latest-windows.zip" || exit /b 1
tar -xf "%~dp0platform-tools.zip" -C "%~dp0." || exit /b 1
del "%~dp0platform-tools.zip"
exit /b 0

:noconn
echo.
echo   Could not connect to the projector.
echo   Wi-Fi: check the IP address and that the PC is on the same Wi-Fi.
echo   USB: check the cable and USB debugging. If Windows shows an unknown device,
echo   it needs the Google USB driver - see README.txt.
echo   Nothing was changed.
pause
exit /b 1

:fail
echo.
echo   Something went wrong, so LumenOS stopped. See the message above.
echo   If any changes were made, LumenOS-Undo.bat puts them back.
pause
exit /b 1
