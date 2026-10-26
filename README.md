# Lumen

**A fast, ad-free home screen and system toolkit for the HY300 projector**, the 1 GB, 32-bit Android 11 projector on the Allwinner H713 that's sold under many brand names.

Lumen replaces the stock launcher and its ads with a tvOS-style home screen. **LumenOS** is a set of Windows tools that clean up the projector around it. Nothing is flashed: every change goes through ADB and can be undone.

Developed with ♥ by Radirig. Open source under the MIT licence.

## What you get

**Lumen (the app, `app/`)**
- tvOS-style home screen: parallax cards, ambient light, a "First Light" startup intro with an original score
- **Continue Watching** above **Trending** (YouTube, Netflix, Prime Video in your country)
- **Lumen Imaging**: picture profiles that drive the projector's own picture processor (Natural, Cinema, HDR-style, Vivid, Game, Bright room), a 5-level **Detail engine**, and **Auto Picture** per app
- **Heat guard**: watches CPU and GPU temperature and steps in before the projector's own 115 °C switch-off
- **Sound Studio**, voice control (English, plus some Hinglish), a phone remote (scan a QR code), casting, **Lumen Window** living scenes
- **Over-the-air updates** from this repository, with a prompt on the home screen

**LumenOS (the toolkit, `lumenos/`)**
| Tool | What it does |
|---|---|
| LumenOS-Install.bat | Removes bloatware and ads, makes Lumen the home screen, renders the UI at the panel's real 720p, ad-blocking DNS |
| LumenOS-Update.bat | Installs a new Lumen by cable or Wi-Fi and gives it its one-time access |
| LumenOS-Logo.bat | Replaces the MAGCUBIC boot logo and boot animation with Lumen's, or keeps the Lumen logo on until the intro |
| LumenOS-Speed.bat | Memory tuning, cached-app freezer, holds back apps that start themselves at boot, startup timeline |
| LumenOS-Keystone.bat | Tunes how steady the automatic keystone is |
| LumenOS-Thermals.bat | 60-second CPU/GPU temperature report |
| LumenOS-Undo.bat | Puts everything back |

See [`lumenos/README.txt`](lumenos/README.txt) and [`app/README.md`](app/README.md) for the details.

## Install on a projector
1. On the projector: Settings → Device Preferences → About → press **Build** 7 times, then turn on **USB debugging** in Developer options.
2. Download **LumenOS-toolkit.zip** from the latest [release](../../releases/latest), unzip it on a Windows PC.
3. Run **LumenOS-Install.bat** and type the projector's IP address (or `USB` for a USB-A to USB-A cable).

From then on Lumen updates itself: when a new version is published, the projector's home screen asks **Update now / Later**.

## How updates work
`ota/update.json` on `main` is the update channel. It names the newest version and points at the signed `ota/Lumen.apk`:
```json
{"versionCode": 17, "versionName": "7.0", "url": "https://raw.githubusercontent.com/irig-16/lumen/main/ota/Lumen.apk", "sha256": "…", "notes": "…"}
```
Projectors check it at startup and every 6 hours. Before installing, Lumen checks the package name, that the version is newer, the signature and the SHA-256; Android then refuses anything not signed with the release key.
Maintainers publish with `app/tools/publish_update.sh "What's new"` and a push; a GitHub Release with the APK and the toolkit is created automatically.

## Repository layout
```
app/        Lumen's source (plain SDK build: aapt, javac, dx; no Gradle)
lumenos/    the Windows toolkit (.bat) and the shell scripts it runs on the projector
ota/        the update channel: update.json + the signed Lumen.apk
```

## Build
```
sudo apt install aapt dalvik-exchange android-sdk-platform-23 apksigner zipalign default-jdk-headless
cd app && ./build.sh
```
Your build is signed with your own key, so it installs on a clean projector but can't update one running the official Lumen. Public builds carry no API keys; enter your own YouTube and TMDB keys on the phone remote (free, see `app/README.md`).

## Safety
- Nothing is flashed, and the bootloader and system partitions aren't touched. The boot-logo tool only adds files to the projector's writable logo area and checks the panel settings file before and after every change.
- The projector's security patch level can't be raised in software: only the vendor can ship a newer signed firmware.
- Provided as is, without warranty (see [LICENSE](LICENSE)). Not affiliated with any projector brand.
