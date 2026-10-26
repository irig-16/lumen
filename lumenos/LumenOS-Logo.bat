@echo off
setlocal
title LumenOS Boot logo
cd /d "%~dp0"
echo.
echo   LumenOS boot logo  (reversible, no flashing)
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
"%ADB%" %SEL% shell mkdir -p /data/local/tmp/lumenlogo
"%ADB%" %SEL% push "%~dp0files\logo.sh" /data/local/tmp/logo.sh >nul || goto :fail
"%ADB%" %SEL% push "%~dp0files\logo\advert.bmp" /data/local/tmp/lumenlogo/advert.bmp >nul || goto :fail
"%ADB%" %SEL% push "%~dp0files\logo\bootlogo.jpg" /data/local/tmp/lumenlogo/bootlogo.jpg >nul || goto :fail
"%ADB%" %SEL% push "%~dp0files\logo\black.zip" /data/local/tmp/lumenlogo/black.zip >nul || goto :fail
"%ADB%" %SEL% push "%~dp0files\logo\ember.zip" /data/local/tmp/lumenlogo/ember.zip >nul || goto :fail
:menu
echo.
echo   ================ Boot logo =================
echo    1  Lumen splash + quiet black animation
echo    2  Lumen splash + Lumen ember animation
echo    3  Lumen hold: the logo stays on until the intro, no black gap
echo    4  Restore the projector's own logo
echo    5  Show what's installed
echo    6  Restart the projector now
echo    7  Quit
echo   ============================================
set "CH="
set /p "CH=  Choose 1-7: "
if "%CH%"=="1" ( "%ADB%" %SEL% shell sh /data/local/tmp/logo.sh install-black & goto :menu )
if "%CH%"=="2" ( "%ADB%" %SEL% shell sh /data/local/tmp/logo.sh install-ember & goto :menu )
if "%CH%"=="3" goto :hold
if "%CH%"=="4" ( "%ADB%" %SEL% shell sh /data/local/tmp/logo.sh restore & goto :menu )
if "%CH%"=="5" ( "%ADB%" %SEL% shell sh /data/local/tmp/logo.sh status & goto :menu )
if "%CH%"=="6" ( "%ADB%" %SEL% reboot & echo   Restarting... & goto :menu )
if "%CH%"=="7" goto :done
goto :menu

:hold
echo.
echo   Lumen hold keeps the Lumen logo on screen until the intro.
echo   Part-way through startup the projector switches to its 720p mode, and the
echo   logo has to be redrawn at that exact moment. So first, a one-time timing check:
echo.
echo     The projector restarts and shows COUNTING NUMBERS where the black gap was.
echo     At one moment the number JUMPS to the middle of the screen and gets BIGGER.
echo     Remember the number that was showing when it jumped.
echo.
echo   Press any key to start the timing check (the projector will restart)...
pause >nul
"%ADB%" %SEL% push "%~dp0files\logo\hold\timing.zip" /data/local/tmp/lumenlogo/timing.zip >nul || goto :fail
"%ADB%" %SEL% push "%~dp0files\logo\hold\hold-full.zip" /data/local/tmp/lumenlogo/hold-full.zip >nul || goto :fail
"%ADB%" %SEL% push "%~dp0files\logo\hold\hold-flat.zip" /data/local/tmp/lumenlogo/hold-flat.zip >nul || goto :fail
"%ADB%" %SEL% shell sh /data/local/tmp/logo.sh install-timing || goto :menu
"%ADB%" %SEL% reboot
echo.
echo   Restarting now. Watch the numbers on the projector...
echo   When Lumen is back on screen, press any key here.
pause >nul
echo   Reconnecting...
call :reconnect || goto :fail
:askn
echo.
echo   Type the number that was showing when it jumped (like 4 or 4.5).
echo   Type 0 if it never jumped, R to watch the timing check again,
echo   or X to cancel and go back to the quiet black animation.
set "N="
set /p "N=  Number: "
if "%N%"=="" goto :askn
set "N=%N:,=.%"
set "N=%N: =%"
if /i "%N%"=="x" ( "%ADB%" %SEL% shell sh /data/local/tmp/logo.sh install-black & goto :menu )
if /i "%N%"=="r" ( "%ADB%" %SEL% reboot & echo   Restarting. Press any key when Lumen is back. & pause >nul & call :reconnect & goto :askn )
if "%N%"=="0" ( "%ADB%" %SEL% shell sh /data/local/tmp/logo.sh install-hold 0 & goto :menu )
echo %N%| findstr /r "^[0-9][0-9]*$" >nul && set "N=%N%.0"
if not exist "%~dp0files\logo\hold\hold-%N%.zip" (
  echo   Please type a number from 0.5 to 15, whole or .5 - like 4 or 4.5
  goto :askn
)
"%ADB%" %SEL% push "%~dp0files\logo\hold\hold-%N%.zip" /data/local/tmp/lumenlogo/hold.zip >nul || goto :fail
"%ADB%" %SEL% shell sh /data/local/tmp/logo.sh install-hold %N%
echo.
echo   Choose 6 to restart and see it. If the logo still jumps or shifts for a moment,
echo   run 3 again and type the number half a second later (or earlier).
goto :menu

:done
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
