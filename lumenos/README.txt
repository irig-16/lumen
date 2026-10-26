LumenOS for the HY300 projector
===============================

Turns the projector's own firmware (Android 11, December 2024 build) into a
clean Lumen system. Nothing is flashed, so there is no risk of bricking it.
Every change can be undone with LumenOS-Undo.bat.

WHAT IT DOES
  1. Removes bloatware: the factory test and aging tools, the vendor OTA
     updaters (they could reinstall stock firmware), the ad-filled app store
     and the office suite. They're removed for your user only, so Undo
     brings them back.
  2. Makes Lumen the home screen, turns the stock launcher (and its ads)
     off, and sets Lumen Window as the screensaver.
  3. Gives Lumen its permissions: updates, drawing over apps, screen presets,
     colour warmth and voice.
  4. Smoothness: the UI is drawn at the panel's real 1280x720 instead of
     1080p (2.25x less GPU work and less heat), animations run at 0.5x, and
     Lumen and Settings are compiled to native code.
  5. Ad blocking for every app through AdGuard DNS (dns.adguard-dns.com).
     If your network blocks it, the script switches it back off by itself.

BEFORE YOU START (once)
  On the projector:
  1. Settings > Device Preferences > About: press OK on "Build" 7 times.
  2. Settings > Device Preferences > Developer options: turn on
     USB debugging (and Network debugging if your menu has it).

  Then connect in ONE of these ways:
  A) Wi-Fi (try this first, even without a Network debugging switch:
     many HY300 firmwares already allow it). Your PC must be on the
     same Wi-Fi. At the prompt, type the projector's IP (Settings >
     Network shows it).
  B) USB cable, if Wi-Fi won't connect. Use a USB-A to USB-A cable
     between the projector's USB port and your PC. At the prompt,
     type USB. If Windows shows the projector as an unknown device
     with no driver, install the Google USB driver once:
     https://developer.android.com/studio/run/win-usb
     (Device Manager > the device > Update driver > Browse > the
     unzipped usb_driver folder).

INSTALL
  Double-click LumenOS-Install.bat and type the IP address, or USB.
  The first time, it downloads Google's adb tool (about 7 MB).
  When the projector asks "Allow network debugging?", tick Always allow
  and press OK. The projector restarts into Lumen.

UPDATE (already ran the installer once?)
  Double-click LumenOS-Update.bat and type the IP address, or USB.
  It installs Lumen 7 over the old one (your settings, keys and pinned
  apps stay) and gives it the one-time access its features need.
  You only do this once: from Lumen 7 on, Lumen updates itself from
  github.com/irig-16/lumen. When a new version is out, the home screen
  asks "Update now / Later".

  Lumen 7 adds:
  - Heat guard: watches CPU and GPU temperature. At CPU 100C a notice,
    at 105C it cools down (background apps cleared, light a step lower),
    at 110C it pauses playback with a full-screen warning, before the
    projector's own switch-off at 115C. If heat ever switches it off,
    the next start tells you. Lumen settings > Heat guard.
  - Free memory for films: when a video starts and memory is low, Lumen
    clears other apps' leftovers so the player gets the room.
  - Over-the-air updates from GitHub, with a prompt on the home screen.
  Lumen 6.9 brought Lumen Imaging (picture profiles, Detail engine,
  Auto Picture) and Continue Watching above Trending.

EXTRA TOOLS
  LumenOS-Keystone.bat - tune the automatic keystone correction (reversible).
    The projector straightens the picture from its tilt sensor. On a shaky
    build it keeps nudging itself. This lets you pick how steady it is:
    Stable (fewest re-corrections, best on a fixed shelf), Balanced (the
    default), or Responsive (re-corrects on smaller tilts), re-run it now,
    or Reset to the original. Nothing is flashed. It can't make the physical
    sensor more accurate - it only changes how it reacts.
  LumenOS-Speed.bat - memory tuning and a faster start (reversible).
    1 Report: startup timeline, memory, and the apps that start themselves
      at every boot (read-only, saved to speed-report.txt).
    2 Apply: keeps fewer apps cached, turns on the cached-app freezer (if
      the projector supports it), holds back non-essential apps from
      starting at boot (they still open normally), compiles apps.
    3 Undo. LumenOS-Undo.bat also undoes it.
  LumenOS-Thermals.bat - samples the CPU and GPU for 60 seconds and reports
    min / average / max against the projector's throttle limits. Read-only.
    Start a video first so the reading reflects real use.

BOOT LOGO
  LumenOS-Logo.bat - replaces the MAGCUBIC startup logo with Lumen.
  Nothing is flashed: the projector's loader shows a logo from its Reserve0
  area, and Android plays a boot animation from /oem/media; both can be
  written over ADB. panel_config.ini (the screen settings next to the logo)
  is never touched, and is checked before and after every change.
    1  Lumen splash + quiet black animation
    2  Lumen splash + Lumen ember animation
    3  Lumen hold - the Lumen logo stays on screen until the intro.
       Needs a one-time timing check: the projector restarts showing
       counting numbers; type the number showing when it jumped to the
       middle and got bigger. If the logo still twitches, run 3 again and
       try half a second later or earlier.
    4  Restore the projector's own logo exactly
  If MAGCUBIC still appears for an instant before the Lumen logo, that is the
  copy in the locked bootloader, which only a firmware flash can change.

UNDO
  Double-click LumenOS-Undo.bat. Removed apps, the stock launcher, the
  screen resolution and all settings go back to how they were.
  Lumen stays installed as a normal app.

IF SOMETHING LOOKS WRONG
  - Internet stopped working: Settings > Network > Private DNS > Automatic,
    or run Undo.
  - Picture or text looks the wrong size: run Undo, or from a PC:
    adb shell wm size reset  and  adb shell wm density reset
  - review.txt in the backup folder lists any other ad or store packages
    the script found but left alone. Send it over to get them handled.
