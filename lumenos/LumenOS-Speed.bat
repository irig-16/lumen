@echo off
setlocal
title LumenOS Speed
cd /d "%~dp0"
echo.
echo   LumenOS Speed: memory tuning and a faster start  (reversible)
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
"%ADB%" %SEL% push "%~dp0files\speed.sh" /data/local/tmp/speed.sh >nul || goto :fail
:menu
echo.
echo   ================== Speed ===================
echo    1  Report: startup timeline, memory, apps that start at boot  (read-only)
echo    2  Apply the speed-ups
echo    3  Undo the speed-ups
echo    4  Restart the projector now
echo    5  Quit
echo   ============================================
set "CH="
set /p "CH=  Choose 1-5: "
if "%CH%"=="1" goto :report
if "%CH%"=="2" goto :apply
if "%CH%"=="3" ( "%ADB%" %SEL% shell sh /data/local/tmp/speed.sh undo & goto :menu )
if "%CH%"=="4" ( "%ADB%" %SEL% reboot & echo   Restarting... & call :reconnect & goto :menu )
if "%CH%"=="5" goto :done
goto :menu

:report
"%ADB%" %SEL% shell sh /data/local/tmp/speed.sh report > "%~dp0speed-report.txt"
type "%~dp0speed-report.txt"
echo.
echo   Saved to speed-report.txt next to this file.
goto :menu

:apply
echo.
echo   This will:
echo    - keep fewer apps cached in memory (more room for the app you're watching)
echo    - turn on the cached-app freezer, if the projector supports it (less CPU use and heat)
echo    - hold back these apps from starting themselves at boot (they still open normally):
"%ADB%" %SEL% shell sh /data/local/tmp/speed.sh list
echo    - compile apps for speed (up to 10 minutes)
echo.
set "OK="
set /p "OK=  Go ahead? (Y/N): "
if /i not "%OK%"=="y" goto :menu
"%ADB%" %SEL% shell sh /data/local/tmp/speed.sh apply
goto :menu

:done
echo.
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

:reconnect
if /i "%IP%"=="usb" ( "%ADB%" %SEL% wait-for-device & exit /b 0 )
for /l %%i in (1,1,40) do (
  "%ADB%" connect "%DEV%" >nul 2>&1
  "%ADB%" %SEL% get-state 2>nul | find "device" >nul && exit /b 0
  ping -n 3 127.0.0.1 >nul
)
exit /b 1

:getadb
if exist "%~dp0platform-tools\adb.exe" exit /b 0
echo   Downloading Android platform-tools (adb) from Google...
curl -L --fail -o "%~dp0platform-tools.zip" "https://dl.google.com/android/repository/platform-tools-latest-windows.zip" || exit /b 1
tar -xf "%~dp0platform-tools.zip" -C "%~dp0." || exit /b 1
del "%~dp0platform-tools.zip"
exit /b 0
