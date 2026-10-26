@echo off
setlocal
title LumenOS Keystone
cd /d "%~dp0"
echo.
echo   LumenOS Keystone tuner  (reversible, no flashing)
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
"%ADB%" %SEL% push "%~dp0files\keystone.sh" /data/local/tmp/keystone.sh >nul || goto :fail
:menu
echo.
echo   ================ Keystone =================
echo    1  Show current keystone settings
echo    2  Stable      ^(fewest re-corrections - best on a fixed shelf^)
echo    3  Balanced    ^(the projector's own default^)
echo    4  Responsive  ^(re-corrects on smaller tilts^)
echo    5  Re-correct now
echo    6  Reset to original
echo    7  Quit
echo   ===========================================
set "CH="
set /p "CH=  Choose 1-7: "
if "%CH%"=="1" ( "%ADB%" %SEL% shell sh /data/local/tmp/keystone.sh status & goto :menu )
if "%CH%"=="2" ( "%ADB%" %SEL% shell sh /data/local/tmp/keystone.sh stable & goto :menu )
if "%CH%"=="3" ( "%ADB%" %SEL% shell sh /data/local/tmp/keystone.sh balanced & goto :menu )
if "%CH%"=="4" ( "%ADB%" %SEL% shell sh /data/local/tmp/keystone.sh responsive & goto :menu )
if "%CH%"=="5" ( "%ADB%" %SEL% shell sh /data/local/tmp/keystone.sh recorrect & goto :menu )
if "%CH%"=="6" ( "%ADB%" %SEL% shell sh /data/local/tmp/keystone.sh reset & goto :menu )
if "%CH%"=="7" goto :done
goto :menu
:done
echo.
echo   Done.
pause
exit /b 0

:noadb
echo   Could not download the adb tool. Check the internet connection and try again.
pause
exit /b 1

:fail
echo.
echo   Could not connect. Check the IP / cable and that debugging is on, then try again.
pause
exit /b 1

:getadb
if exist "%~dp0platform-tools\adb.exe" exit /b 0
echo   Downloading Android platform-tools (adb) from Google...
curl -L --fail -o "%~dp0platform-tools.zip" "https://dl.google.com/android/repository/platform-tools-latest-windows.zip" || exit /b 1
tar -xf "%~dp0platform-tools.zip" -C "%~dp0." || exit /b 1
del "%~dp0platform-tools.zip"
exit /b 0
