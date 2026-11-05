@echo off
setlocal
title LumenOS Update
cd /d "%~dp0"
echo.
echo   LumenOS Update: installs the newest Lumen over yours (settings stay) and gives it
echo   the one-time access its features need. You only need this once: Lumen updates itself.
echo.
call :getadb || goto :noadb
set "IP=%~1"
if "%IP%"=="" set /p "IP=  Projector IP address, or type USB if it is connected by cable: "
if "%IP%"=="" goto :fail
set "ADB=%~dp0platform-tools\adb.exe"
set "DEV=%IP%:5555"
if /i "%IP%"=="usb" (set "SEL=-d") else (set "SEL=-s %IP%:5555")
if /i not "%IP%"=="usb" "%ADB%" connect "%DEV%"
if /i "%IP%"=="usb" "%ADB%" devices >nul 2>&1
echo.
echo   If the projector asks "Allow USB debugging?" or "Allow network debugging?", press OK, then press any key here.
pause >nul
if /i not "%IP%"=="usb" "%ADB%" connect "%DEV%" >nul 2>&1
"%ADB%" %SEL% get-state 2>nul | find "device" >nul || goto :fail
echo.
echo   Installing Lumen...
call :getapk || goto :fail
"%ADB%" %SEL% install -r "%~dp0files\Lumen.apk" || goto :fail
"%ADB%" %SEL% push "%~dp0files\lumenos-update.sh" /data/local/tmp/lumenos-update.sh >nul || goto :fail
"%ADB%" %SEL% push "%~dp0files\lumenos-undo.sh" /data/local/tmp/lumenos-undo.sh >nul
"%ADB%" %SEL% shell sh /data/local/tmp/lumenos-update.sh || goto :fail
echo.
echo   Done. Lumen has restarted with the update. From now on it updates itself.
pause
exit /b 0

:noadb
echo   Could not download the adb tool. Check the internet connection and try again.
pause
exit /b 1

:fail
echo.
echo   Could not finish the update. Check the connection and try again.
pause
exit /b 1

:getapk
if exist "%~dp0files\Lumen.apk" exit /b 0
for %%F in ("%~dp0Lumen*.apk" "%~dp0..\Lumen*.apk") do if exist "%%~F" (
  copy /y "%%~F" "%~dp0files\Lumen.apk" >nul
  echo   Using %%~nxF
  exit /b 0
)
echo   Downloading the latest Lumen from GitHub...
curl -L --fail -o "%~dp0files\Lumen.apk" "https://raw.githubusercontent.com/irig-16/lumen/main/ota/Lumen.apk" || exit /b 1
exit /b 0

:getadb
if exist "%~dp0platform-tools\adb.exe" exit /b 0
echo   Downloading Android platform-tools (adb) from Google...
curl -L --fail -o "%~dp0platform-tools.zip" "https://dl.google.com/android/repository/platform-tools-latest-windows.zip" || exit /b 1
tar -xf "%~dp0platform-tools.zip" -C "%~dp0." || exit /b 1
del "%~dp0platform-tools.zip"
exit /b 0
