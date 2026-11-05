@echo off
setlocal EnableDelayedExpansion
title LumenOS Rescue
cd /d "%~dp0"
echo.
echo   LumenOS Rescue: for a projector stuck on its boot logo
echo   ======================================================
echo   Leave the projector ON, showing its logo. Use this PC - the one you
echo   installed LumenOS from - because the projector already trusts it.
echo.
call :getadb || goto :noadb
set "ADB=%~dp0platform-tools\adb.exe"
set "IP=%~1"
if "%IP%"=="" set /p "IP=  Projector IP address, or type USB if it is connected by cable: "
if "%IP%"=="" goto :noconn
if /i "%IP%"=="usb" (set "SEL=-d") else (set "SEL=-s %IP%:5555")

echo.
echo   Looking for the projector (up to 2 minutes; it may still be starting)...
set /a N=0
:find
if /i not "%IP%"=="usb" "%ADB%" connect %IP%:5555 >nul 2>&1
"%ADB%" devices > "%TEMP%\lumen_devices.txt" 2>&1
findstr /i "unauthorized" "%TEMP%\lumen_devices.txt" >nul && goto :unauth
"%ADB%" %SEL% get-state 2>nul | find "device" >nul && goto :found
set /a N+=1
if !N! geq 24 goto :noconn
timeout /t 5 /nobreak >nul
goto :find

:found
echo   Connected.
set "LOG=%~dp0rescue-log.txt"
echo   Saving what the projector reports to rescue-log.txt (send it over so Lumen can be fixed)...
> "%LOG%" echo === LumenOS Rescue %DATE% %TIME% ===
for %%P in (ro.product.brand ro.product.model ro.build.version.release ro.build.version.sdk ro.board.platform ro.hardware ro.product.board ro.build.display.id sys.boot_completed init.svc.bootanim) do (
  for /f "delims=" %%V in ('call "%ADB%" %SEL% shell getprop %%P 2^>nul') do >> "%LOG%" echo %%P = %%V
)
>> "%LOG%" echo.
>> "%LOG%" echo === LumenOS log ===
"%ADB%" %SEL% shell cat /data/local/tmp/lumenos/lumenos.log >> "%LOG%" 2>&1
>> "%LOG%" echo === disabled by LumenOS ===
"%ADB%" %SEL% shell cat /data/local/tmp/lumenos/disabled.txt >> "%LOG%" 2>&1
>> "%LOG%" echo === removed by LumenOS ===
"%ADB%" %SEL% shell cat /data/local/tmp/lumenos/removed.txt >> "%LOG%" 2>&1
>> "%LOG%" echo === home screens ===
"%ADB%" %SEL% shell "cmd package query-activities --components -a android.intent.action.MAIN -c android.intent.category.HOME 2>/dev/null; pm list packages -d" >> "%LOG%" 2>&1
>> "%LOG%" echo === crashes ===
"%ADB%" %SEL% shell "logcat -d -b crash 2>/dev/null | tail -n 200" >> "%LOG%" 2>&1
>> "%LOG%" echo === recent errors ===
"%ADB%" %SEL% shell "logcat -d -t 1500 2>/dev/null | grep -iE 'lumen|FATAL|AndroidRuntime|ActivityManager|home|launcher|PackageManager|died|ANR' | tail -n 250" >> "%LOG%" 2>&1

echo.
"%ADB%" %SEL% push "%~dp0files\rescue.sh" /data/local/tmp/rescue.sh >nul || goto :fail
if exist "%~dp0files\lumenos-undo.sh" "%ADB%" %SEL% push "%~dp0files\lumenos-undo.sh" /data/local/tmp/lumenos-undo.sh >nul
"%ADB%" %SEL% shell sh /data/local/tmp/rescue.sh > "%TEMP%\lumen_rescue.txt" 2>&1
type "%TEMP%\lumen_rescue.txt"
>> "%LOG%" echo === rescue ===
type "%TEMP%\lumen_rescue.txt" >> "%LOG%"
findstr /c:"Rescue done" "%TEMP%\lumen_rescue.txt" >nul || goto :fail

echo.
echo   Restarting the projector...
"%ADB%" %SEL% reboot
timeout /t 25 /nobreak >nul
set /a N=0
:boot
if /i not "%IP%"=="usb" "%ADB%" connect %IP%:5555 >nul 2>&1
for /f "delims=" %%B in ('call "%ADB%" %SEL% shell getprop sys.boot_completed 2^>nul') do if "%%B"=="1" goto :booted
set /a N+=1
if !N! geq 36 goto :slow
timeout /t 5 /nobreak >nul
goto :boot

:booted
echo.
echo   The projector has started with its own launcher. Lumen is switched off, not deleted.
echo   Please send rescue-log.txt (next to this file) so Lumen can be fixed for this projector.
echo.
pause
exit /b 0

:slow
echo.
echo   The fix is applied, but the projector hasn't reported a finished start after 3 minutes.
echo   Give it a few more minutes. If it is still on the logo, unplug it for 10 seconds and
echo   plug it back in. If that doesn't help, see "If nothing connects" in README.txt.
echo   Please send rescue-log.txt (next to this file).
pause
exit /b 1

:unauth
echo.
echo   The projector doesn't trust this PC, and it can't show the "Allow" prompt while it is
echo   stuck. Run this on the PC you installed LumenOS from. If that isn't possible, see
echo   "If nothing connects" in README.txt (a factory reset from the recovery menu).
pause
exit /b 1

:noconn
echo.
echo   Could not reach the projector.
echo   - Wi-Fi often isn't up yet while it's stuck on the logo: connect it with the USB-A to
echo     USB-A cable and run this again, typing USB.
echo   - Otherwise see "If nothing connects" in README.txt (a factory reset from the
echo     recovery menu puts everything back, because LumenOS never touched the system itself).
pause
exit /b 1

:fail
echo.
echo   The rescue stopped part-way. Run it again; if it keeps stopping, send rescue-log.txt.
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
