@echo off
setlocal EnableDelayedExpansion
title LumenOS Picture Fix
cd /d "%~dp0"
echo.
echo   LumenOS Picture Fix: for a projector that went dark after a picture mode
echo   ========================================================================
echo   Leave the projector on. This brings its picture back, installs a Lumen that
echo   tests picture changes safely, and saves picture-log.txt to send over.
echo.
call :getadb || goto :noadb
set "ADB=%~dp0platform-tools\adb.exe"
set "IP=%~1"
if "%IP%"=="" set /p "IP=  Projector IP address, or type USB if it is connected by cable: "
if "%IP%"=="" goto :noconn
if /i "%IP%"=="usb" (set "SEL=-d") else (set "SEL=-s %IP%:5555")
if /i not "%IP%"=="usb" "%ADB%" connect %IP%:5555 >nul 2>&1
"%ADB%" %SEL% get-state 2>nul | find "device" >nul || goto :noconn

set "LOG=%~dp0picture-log.txt"
> "%LOG%" echo === LumenOS Picture Fix %DATE% %TIME% ===
for %%P in (ro.product.brand ro.product.model ro.build.version.release ro.board.platform ro.hardware ro.product.board) do (
  for /f "delims=" %%V in ('call "%ADB%" %SEL% shell getprop %%P 2^>nul') do >> "%LOG%" echo %%P = %%V
)
>> "%LOG%" echo === picture settings now ===
"%ADB%" %SEL% shell "for k in screen_brightness screen_brightness_mode; do echo system $k = $(settings get system $k); done; for k in night_display_activated night_display_color_temperature; do echo secure $k = $(settings get secure $k); done" >> "%LOG%" 2>&1
>> "%LOG%" echo === backlight ===
"%ADB%" %SEL% shell "for d in /sys/class/backlight/*; do echo $d: $(cat $d/brightness 2>/dev/null) / $(cat $d/max_brightness 2>/dev/null) actual $(cat $d/actual_brightness 2>/dev/null); done; dumpsys display 2>/dev/null | grep -iE 'brightness|backlight' | head -n 30; dumpsys power 2>/dev/null | grep -iE 'brightness' | head -n 15" >> "%LOG%" 2>&1

echo.
if exist "%~dp0Lumen.apk" (
  echo   Installing Lumen 8.2 ^(it puts the projector's own brightness and night light back^)...
  "%ADB%" %SEL% install -r "%~dp0Lumen.apk" >> "%LOG%" 2>&1
  "%ADB%" %SEL% shell am start -a android.intent.action.MAIN -c android.intent.category.HOME >nul 2>&1
  timeout /t 8 /nobreak >nul
  call :ask "Lumen 8.2 installed" && goto :fixed
)

"%ADB%" %SEL% shell settings put secure night_display_activated 0 >nul 2>&1
call :ask "Night light off" && goto :fixed
for %%B in (102 255 160 40) do (
  "%ADB%" %SEL% shell "settings put system screen_brightness_mode 0; settings put system screen_brightness %%B" >nul 2>&1
  call :ask "Brightness setting %%B" && goto :fixed
)
"%ADB%" %SEL% shell settings put system screen_brightness_mode 1 >nul 2>&1
call :ask "Automatic brightness" && goto :fixed
echo.
echo   Not back yet. Unplug the projector for 10 seconds and plug it in again.
echo   Please send picture-log.txt (next to this file).
>> "%LOG%" echo RESULT: not back after all steps
pause
exit /b 1

:fixed
echo.
echo   Picture is back. Lumen 8.2 won't change this projector's picture again without
echo   a 10-second test you confirm on screen. Please send picture-log.txt.
pause
exit /b 0

:ask
echo.
echo   Tried: %~1
choice /c YN /n /m "  Can you see the picture on the projector now? [Y/N] "
if errorlevel 2 (>> "%LOG%" echo %~1: still dark & exit /b 1)
>> "%LOG%" echo %~1: PICTURE BACK
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
