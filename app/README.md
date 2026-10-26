# Lumen 7 — a tvOS-inspired home screen for the HY300 projector

A ~400 KB launcher built for 1 GB RAM, 32-bit Android projectors (Allwinner H713, Mali-G31).
It updates itself over the air, casts from phones and laptops, turns into a living window when idle, and has a cinematic startup intro, trending rows (YouTube, Netflix, Prime Video), voice control, a phone remote you connect by scanning a QR code, Sound Studio, Screen presets and Picture Lab.

Developed with ♥ by Radirig.

## Colours
The interface uses one palette: **Jet Black** `#0A0A0A` backgrounds, **Cerulean Blue** `#007BA7` for buttons, chips, toggles and sliders, and **Neon Rose** `#FF2D95` for focus, selection and highlights.
App cards keep each app's own colour; everything else carries the palette, including the phone remote.

## Startup intro: "First Light"
Every time the projector starts, Lumen plays a 10-second intro about light (Lumen means light), with an original score:
- **0.6–1.6 s:** darkness; a small warm ember breathes, over a low hum.
- **1.6 s:** it ignites into a living spark (a bright electric click) and darts away.
- **2.4–5.3 s:** the spark swoops back and touches each letter of L U M E N in turn. Each one flickers on like a warming filament, with a rising chime, a ring of light and a spray of sparkles; the chimes travel left to right with the letters.
- **6 s:** the spark shoots up and bursts: light rays flood out from behind the word, a room appears with the glowing letters reflected in a dark glossy floor, and a warm chord blooms.
- **7–8 s:** "CINEMA AT HOME" and the credit; dust drifts through the light, then it dissolves into the home screen.

**Sound and picture stay in sync.** The projector's lamp and panel take a few seconds to show anything after boot.
Lumen now waits until the first frame is actually on screen, adds a short settle delay (longer right after boot), and only then starts the music and the animation together, so nothing is skipped.

**With a home theatre or Bluetooth speaker:**
- **Speaker reconnecting at boot:** if your sound normally goes to Bluetooth, the intro waits up to 12 seconds for that speaker to reconnect ("Connecting to your speaker…"), so the music doesn't start on the projector and jump across halfway.
- **Wake-up lead-in:** the music file starts with 1.2 s of silence that wakes the Bluetooth link or speaker before the first note. It's skipped on the projector's own speaker.
- **Bluetooth delay:** the animation follows the music as it actually comes out of the speaker (Android reports Bluetooth's ~0.2 s delay), so the 6-second hit lands on the flare.
- A receiver that auto-switches inputs or sleeps on silence can still clip the start. Set it to the projector's input and turn its auto-standby / signal-sense off if that happens.

Controls:
- **Any button** skips it.
- In Lumen settings, **Startup intro** turns it off and **Play the intro now** replays it.
- You can also say "play the intro".
- It plays once per boot, not every time you press HOME.

## Trending: YouTube, Netflix and Prime Video
Up to three rows show what's popular in your country. Each follows the projector's Wi-Fi location.

| Row | Source | Refresh | OK does |
|---|---|---|---|
| Trending on YouTube | YouTube's official chart | every 3 h | plays it in SmartTube or YouTube |
| Popular on Netflix | TMDB: titles streaming on Netflix in your country, by popularity | every 12 h | opens Netflix on a search for the title |
| Popular on Prime Video | TMDB: the same for Prime Video | every 12 h | opens Prime Video on a search for the title |

Netflix and Prime Video don't publish their charts or let other apps read your account. So these rows show what's popular on each service in your country right now, not your personal list or their exact Top 10.

**Turning trending off.**
- Lumen settings → **Show trending rows** hides all of them at once (and stops all fetching).
- The phone remote's **Trending** card has the same master switch plus one switch per row.
- On a trending card, press **MENU** for *Hide this row* or *Turn off all trending*.
- Saying "what's trending" turns them back on.

**Keys.** Lumen ships with no API keys. Out of the box, trending, "where to watch" and Lumen Window go through **Lumen's key service** ([`proxy/`](../proxy/README.md)), a small Cloudflare Worker that adds the keys on its side and keeps them private. If you enter your own keys on the phone remote (More → Trending → Keys and country), Lumen uses those directly instead.

**Getting your own keys (free).** Paste them on the phone remote and tap **Save**.
1. **YouTube:** on a computer open console.cloud.google.com, create a project, enable **YouTube Data API v3**, then **Credentials → Create credentials → API key**. Each refresh uses 1 of the 10,000 free daily requests.
2. **Netflix + Prime Video:** make a free account at themoviedb.org, then **Settings → API**, request a personal key, and paste either the **API key** or the **API read access token**. One key covers both rows.

*This product uses the TMDB API but is not endorsed or certified by TMDB.* Streaming availability on TMDB comes from JustWatch.

**Country follows your Wi-Fi.**
- Lumen asks Cloudflare which country the projector's internet connection is in, using a lightweight request that needs no key.
- It checks again every 6 hours and whenever the projector joins a network, so if you take it to India it switches to India by itself.
- If you use a VPN, the VPN's country is what Lumen sees.
- To pin a country instead, type a 2-letter code (IN, GB, …) on the phone card. Clear it to go back to automatic.

Images are cached, so the rows appear instantly after a reboot.

## Lumen Window: living scenes
Ten real outdoor video scenes, full screen: **ocean waves, snowy mountains, rain in the forest, city at night, waterfall, northern lights, desert dunes, autumn forest, coral reef and a lake at sunrise**. A new scene crossfades in every 3 minutes, and each one drifts very slowly so it feels like a view, not a video.

**Getting the scenes (once, then offline).**
1. Your Pixabay key is built in. On the phone remote: **More → Window**, tap **Download scenes**. About 60 MB at 720p; progress shows on the phone.
2. That's it: the clips stay on the projector and play without internet. **Get new scenes** swaps them for different clips any time.
- **Add your own videos as scenes** (same card) sends videos from your phone into the rotation; they're kept too.
- Clips are free stock footage from Pixabay; each scene shows its creator for a few seconds when it appears. Scenes play silently.

**Starting it.**
- Automatically after 10 minutes with no buttons on the home screen (Lumen settings → **Window after idle**: off, 5, 10, 20 or 30 minutes).
- Say "window mode", "screensaver", "show me the beach", "take me to the mountains", "northern lights"…
- Phone remote: **More → Open Lumen Window**.
- As Android's own screensaver too (Settings → Display → Screen saver → **Lumen Window**), where the projector's settings offer it, so it also appears when another app is left idle.

**Remote:** ◀ ▶ (or ▲ ▼) change scene · OK shows the scene's name · BACK (or any other button) returns home.

## Cast: screen sharing
Open **Cast** in the top bar, tap the cast icon at the top of the phone remote, or say "screen share" / "mirror my phone". There are three ways to put something on the projector:

**1. Mirror your phone.** Lumen finds the projector's own mirroring receiver (Miracast, AirPlay, EShare, Google Cast…) and opens it. Then on the phone use **Quick settings → Cast / Smart View** (Android) or **Control Centre → Screen Mirroring** (iPhone). Phone browsers can't share the phone's screen themselves, so this uses the projector's receiver. If none is installed, the Cast screen says so.

**2. Share a computer screen (no install).** On a laptop on the same Wi-Fi, open `https://<projector-ip>:8443/share` in Chrome or Edge (the link and PIN are on the Cast screen and in the remote's Cast sheet), tap **Start sharing** and choose a screen, window or tab.
- **Smooth** for video and motion (up to 15 frames a second), **Sharp text** for documents and code.
- A still screen is only resent about once a second, which saves Wi-Fi and the projector's processor.
- The newest sender takes over the screen. **Back** on the projector stops the share, and the laptop is told.
- Sound stays on the laptop. Expect a short delay: it's great for slides, browsing and photos, not for films (play those on the projector instead).
- The browser warns once about the projector's own certificate: **Advanced → Proceed**.

**3. Send photos and videos from the phone.** In the remote's Cast sheet, tap **Send photos and videos** and pick one or several. They go over Wi-Fi to the projector and show full-screen: photos at screen size and the right way up, videos and music in the player.
- **◀ ▶** browse (in a video they skip 10 s), **OK** pauses and plays, **▲ ▼** step through what was sent, **Back** closes.
- The phone remote's arrows and play button work here too.
- The last 30 items are kept until the projector restarts; Lumen always leaves 300 MB free.

## Updates over the air
Lumen is open source and updates itself from this repository. Your settings, keys, pinned apps and hidden apps stay as they are.

**How it works.**
- The update channel is [`ota/update.json`](../ota/update.json) on `main`: `{"versionCode", "versionName", "url", "sha256", "notes"}`, pointing at the signed `ota/Lumen.apk`.
- Lumen checks it when it starts and every 6 hours. When a newer version is out, the home screen asks: **Update now** or **Later** (Later asks again a day later).
- **Update now** downloads the APK, checks it, and Android shows its Install screen: press OK on the remote. Lumen restarts on the new version and shows what's new.
- You can also check by voice ("check for updates", "update Lumen") or in Lumen settings → Updates, and turn automatic checks off there.

**What Lumen checks before installing.** The file must be Lumen, newer than the installed version, signed with the same key, and match the SHA-256 in `update.json`. Android itself refuses an update signed with a different key.

**Other sources.** On the phone remote's **Updates** card you can point Lumen at another GitHub repo (`owner/repo`, its latest release with `Lumen.apk` attached) or any https `update.json`. **Send an APK** installs a Lumen build straight from your phone over Wi-Fi.

**Publishing a version (maintainers).** Raise `versionCode`/`versionName` in `AndroidManifest.xml`, then:
```
tools/publish_update.sh "What's new"
```
It builds a key-free public APK signed with the release key, writes `ota/Lumen.apk` and `ota/update.json`, and commits them. Push to `main` and every projector offers the update within 6 hours (at once if it restarts). A GitHub Release with the same files is created automatically.

The first time, Android may ask you to allow Lumen to install apps (Lumen settings → Updates → Allow). If this projector hides that screen, LumenOS-Update grants it, or run once:
```
adb shell appops set com.lumen.launcher REQUEST_INSTALL_PACKAGES allow
```

## Heat guard
The HY300 runs hot (CPU around 95 °C under load) and switches itself off at 115 °C (GPU: 110 °C). Lumen watches both and steps in first:

| CPU / GPU | What Lumen does |
|---|---|
| 100 / 95 °C | A short notice: keep the vents clear |
| 105 / 100 °C | Cools down: clears background apps, lowers the light a step, until it's back under the line |
| 110 / 105 °C | Pauses playback and shows a full-screen warning |
| 115 / 110 °C | The projector's own protection switches it off |

If the projector does switch itself off from heat, the next start says so, with the temperature it reached.
Lumen settings → **Heat guard** shows the live temperatures and today's maximum, turns it off, or makes it step in 5 °C earlier. Ask "how hot is the projector" any time.
Exact temperatures need the one-time access LumenOS-Update gives (Android's thermal service); without it Lumen reads the sensors directly where the firmware allows.

**Free memory for films.** When a video app starts and memory is below 300 MB, Lumen clears other apps' leftover background processes so the player gets the room. Toggle it in Lumen settings.

## Phone remote: scan to connect
- On the projector, press **MENU** → **Phone remote**. A **QR code** appears; scan it with the phone camera.
  (On a projector without a microphone, the 🎤 button shows the same QR code.)
- The QR opens the remote with the PIN already filled in. It uses the secure address when it's ready, so the big mic button works straight away.
- The first page is a real remote: **Back, Home, Menu**, a round **D-pad with OK** (hold an arrow to repeat), **keyboard**, **mic**, **mute**, a **VOL** rocker, a **TRACK** rocker and **play/pause**.
- The other tabs: **Apps** (search and open), **Sound** (presets, live EQ curve, output, fine-tune), **Screen** (picture presets and calibration) and **More** (Trending, Updates).
- **What the buttons control:** volume, mute, play/pause and track work in every app. Home always brings you back to Lumen. The D-pad, OK, Back, Menu and typing work on Lumen's own screens. The keyboard types into the open text box; if none is open, it runs what you type as a command or search.

## Screen presets
Like Sound Studio, but for the picture. Open **Screen** in the top bar, or say "HDR picture", "cinema picture" or "bright room mode".

| Preset | For | Automatic part (when allowed) |
|---|---|---|
| Standard | everyday | restores your own brightness, neutral colour |
| Cinema | films in a dark room, most accurate | brightness 75 %, warm colour |
| Vivid (HDR-style) | punchy colour and contrast | brightness 100 % |
| Bright room | daytime, lights on | brightness 100 % |

Each preset also lists the exact values to set in the **projector's own picture menu** (picture mode, contrast, colour, sharpness, colour temperature, dynamic contrast, MEMC, keystone). That menu is where most of the image processing lives, and apps can't change it directly.

To let presets change brightness and warmth by themselves, tap **Allow automatic** in the Screen panel, or run once from a computer:
```
adb shell appops set com.lumen.launcher WRITE_SETTINGS allow
adb shell pm grant com.lumen.launcher android.permission.WRITE_SECURE_SETTINGS
```
The first line allows brightness; the second allows colour warmth (Android's night-light filter).

A 720p LCD projector can't show real HDR or Dolby Vision; Vivid gives the closest look.

## Voice control
There are two ways to give commands, and both use the same command engine.

**1. Talk from your phone, with its own microphone (no extra hardware)**
1. Put your phone on the same Wi-Fi as the projector.
2. On the projector, press **MENU** on the home screen and open **Phone remote** to see the addresses and PIN.
3. On your phone, open the address it shows (for example `http://192.168.1.23:8090`) and enter the PIN.
4. Tap **Turn on the big mic button**. This opens the secure page (`https://…:8443`).
   - The browser warns that the connection isn't private, because the projector made its own certificate.
   - Tap **Advanced → Proceed**. It's your projector on your own Wi-Fi.
   - Phone browsers only allow the microphone on secure (HTTPS) pages; that's the only reason for this step.
5. Tap the glowing orb and talk. Your words appear live, and the command runs as soon as you stop.
6. Tip: in Chrome, use **⋮ → Add to Home screen** to get a one-tap Lumen remote icon.

The page also has play/pause, next, previous and volume buttons, the full Sound Studio and Picture Lab shortcuts. They all work while a video is playing.

To let the phone **open apps while something else is on screen**, allow it once from a computer:
```
adb shell appops set com.lumen.launcher SYSTEM_ALERT_WINDOW allow
```

**2. Talk to the projector**
- Press the 🎤 button in the top bar, or the voice or search button on a voice remote.
- This needs a microphone, such as a Bluetooth voice remote, a headset or a USB mic, plus a speech engine on the projector (usually the Google app).
- If either is missing, Lumen opens a box where you can type the command instead and shows the phone-remote address.

**What it understands.** Speak naturally: wake words ("hey Lumen") and polite filler ("can you", "please") are ignored.

| Say | Does |
|---|---|
| open Kodi · launch VLC · netflix · YouTube kholo | opens the app (fuzzy: "cody" → Kodi) |
| play lofi hip hop · search cat videos on YouTube | YouTube search (SmartTube / YouTube); “play …” without an app stays YouTube |
| search Stranger Things on Netflix · find The Boys on Prime · Netflix search Squid Game · open Netflix and search for Dark | searches inside that app (Netflix, Prime Video, Disney+ / Hotstar, Apple TV, Shahid, Plex, Kodi… any installed app) |
| play Coldplay on Spotify · listen to Arijit Singh on Anghami · on Netflix play Bridgerton | starts it in that app when the app supports it, otherwise searches |
| watch Oppenheimer · watch The Bear | looks the title up and opens it on whichever installed service streams it; otherwise searches YouTube |
| where can I watch Interstellar · is Dune on Netflix · which app has The Office | answers with the services streaming it in your country (TMDB / JustWatch data) |
| what's popular on Netflix · what's trending on Prime · Netflix trending | jumps to that trending row (and turns it on) |
| fast forward · rewind · skip forward · go back 10 seconds · next episode | seeking in what's playing (apps decide how far) |
| google weather in Dubai | web search |
| pause · play · next · previous · stop · ruko | controls whatever is playing |
| volume up / down · volume 30 · set the volume to fifty · mute · awaaz badhao | volume |
| go home · open Wi-Fi / Bluetooth / display / settings | navigation |
| free up memory · how much RAM is free | memory |
| what time is it · what's the date | spoken and on-screen answer |
| add Kodi to the top row · remove Netflix from favourites · hide Photos · uninstall Hotstar | manage apps |
| calm mode · full motion · pure black · ambient light | Lumen look |
| home theatre sound · I'm using a soundbar · bluetooth speaker mode · headphones mode · projector speaker · detect the speakers | where the sound goes (Sound Studio tuning) |
| lumen sound · cinema sound (or cinema mode) · dialogue mode · music mode · night mode · sound effects off | Sound Studio presets |
| compare the sound | A/B: hear Off for a moment |
| what's trending · play the intro | Trending rows (your Wi-Fi's country; turns them back on) · replay the intro |
| more bass · less bass · clearer voices · I can't hear the dialogue · wider sound · volume boost | Sound Studio adjustments |
| open sound settings · equalizer | opens Sound Studio |
| picture lab · help me focus · keystone grid · adjust the brightness · set contrast · sharpness test · colour bars | Picture Lab patterns |
| picture settings · projector settings | the projector's own picture menu |
| screen presets · HDR · dolby vision · vivid picture · cinema picture · bright room mode · standard picture | Screen presets |
| window mode · screensaver · show me the beach · take me to the mountains · northern lights · coral reef | opens Lumen Window (at that scene) |
| screen share · share my laptop · cast · show my photos | opens Cast |
| mirror my phone · screen mirroring · airplay · miracast | opens the projector's mirroring receiver |
| check for updates · update Lumen · is there a new version | checks the update source, or installs a ready update |
| how hot is the projector · projector temperature | CPU and GPU temperature (heat guard) |
| cinema sound and more bass · open Kodi then volume 30 | several commands at once |
| help | ideas |

Replies are shown on a glowing orb overlay and spoken aloud if the projector has a text-to-speech voice.
You can turn spoken replies off in Lumen settings. Playback and volume commands stay silent so they don't talk over your movie.

## Sound Studio (v2: speaker-aware and level-matched)
Open it with the speaker button in the top bar, by saying "open sound settings", or from the phone.

**Why v1 sounded worse than Off.** It boosted deep bass that a 3 W projector speaker can't reproduce. That only uses up amplifier headroom and causes distortion. v2 is tuned the way small speakers are tuned:
1. Remove sub-bass below 90 Hz. This frees headroom, so the sound is cleaner and louder.
2. Lift 120–300 Hz slightly. This is the lowest range the speaker can actually play, so it's where you hear bass.
3. Dip the boxy 300–600 Hz range.
4. Add presence at 1.2–3 kHz so voices cut through, plus a little air on top.
5. Apply gentle compression and make-up gain, so every preset is at least as loud as Off. In any comparison, louder sounds "better", so the presets are level-matched. A limiter makes sure nothing clips.

| Preset | Best for |
|---|---|
| **Lumen** | everyday: the best all-round setting for this speaker |
| Cinema | films: more punch and presence |
| Dialogue | news, talk and quiet actors: strong voice clarity |
| Music | warmer low end, more sparkle |
| Night | late night: loud scenes tamed, voices lifted |
| Off | bypasses everything |

You can also tune it yourself:
- **Fine-tune sliders:** Bass, Treble, Clarity (±6 dB) and Loudness (±4 dB), all relative to the preset's own tuning. **Reset** puts them back.
- **Surround** only switches on for wired headphones. On a single small speaker it makes the sound hollow.
- **A/B compare** lets you hear Off for a moment, then it switches back by itself, so you can judge by ear.

### Sound goes to (home theatre, speakers, headphones)
The right tuning depends on where the sound ends up, so Sound Studio has an output setting (in the panel, on the phone, or by voice):

| Output | When | Tuning |
|---|---|---|
| **Auto** (default) | — | Home theatre whenever a cable or Bluetooth sound system is connected, otherwise Projector speaker |
| Projector speaker | nothing connected | the small-speaker tuning above |
| **Home theatre** | soundbar, AV receiver or good speakers, by cable or Bluetooth | near-flat; real sub-bass allowed; Cinema and Music keep the film's full dynamics (no compression); only Night squeezes loud scenes |
| Bluetooth speaker | a portable speaker | real bass, moderate compression |
| Headphones | wired headphones | like Bluetooth speaker, plus Surround |

- Android reports a cable in the 3.5 mm jack exactly like headphones, so Auto treats a cable as a home theatre. Pick **Headphones** if that's what's plugged in.
- Optical, USB DACs and USB soundbars count as a cable too.
- Say "home theatre sound", "I'm using a soundbar", "bluetooth speaker mode", "headphones mode", "projector speaker" or "detect the speakers".
- **Tip for a home theatre on a cable:** set the projector's volume high (around 80–100 %) and control the level on the receiver or soundbar. That keeps the signal well above the cable's noise floor.

**Seeing what it does.** Sound Studio on the projector and the phone's Sound tab both show the EQ curve being applied, and update it as you change presets or sliders. They also say whether the engine is **Active** on the projector. If another sound effect on the projector takes control, Sound Studio says so and takes over again as soon as it can (it now asks Android for the highest priority).

How it works:
- On Android 9+ it uses the **precision engine**: an 8-band EQ, a compressor and a limiter in one effect. Older firmware gets a simpler fixed-band EQ instead.
- If the firmware blocks system-wide effects, it applies inside VLC, Kodi or MX Player instead.
- It is not licensed Dolby processing. With a home theatre, the receiver's own Dolby / DTS modes still apply on top of what arrives from the projector.

## Picture Lab (image clarity)
Open it with the picture button in the top bar, by saying "help me focus", or from the phone.
It shows six full-screen patterns, drawn pixel-exact at the projector's native resolution:
1. **Focus:** a Siemens star plus 1-pixel line gratings in the centre and corners. Turn the focus wheel until they are crisp.
2. **Geometry and keystone:** a frame, grid and circles. Align the projector physically and keep digital keystone at 0, because keystone throws away pixels and softens the image.
3. **Black level:** 0–15% bars. Set **Brightness** so 2% is just visible and 0% disappears.
4. **White level:** 84–100% bars. Set **Contrast** so 96, 98 and 100% stay separate.
5. **Sharpness:** edges and thin lines. Lower **Sharpness** until there are no halos.
6. **Gradient and colour:** ramps and colour bars, to check for banding and colour tint.

Controls:
- **◀ ▶** changes the pattern.
- **OK** opens the projector's own picture menu, which Lumen finds automatically on most firmwares.
- **▲ ▼** hides the tips.

Software can't add resolution. Correct focus, geometry and levels are the biggest real-world gains on a 720p LCD projector, and that is what Picture Lab helps you set.

## UI sounds
- When focus moves between apps, you hear a soft tick, and an app opening gives a deeper "thock".
- Holding the D-pad down won't make it buzz.
- You can turn UI sounds off in Lumen settings.

**Privacy and security**
- The phone remote only listens on your home network, on port 8090 (HTTP) and port 8443 (HTTPS, for the mic).
- The HTTPS certificate is generated on the projector the first time it starts. Its private key never leaves the device.
- Every request needs the 4-digit PIN.
- After five wrong PINs, the remote locks for 30 seconds.
- You can change the PIN or turn the phone remote off in Lumen settings.

## What it looks like
- **Top shelf:** the focused app is shown big, with its title, artwork and live RAM and storage readouts.
- **Ambient light:** soft aurora light behind everything takes on the focused app's colour and cross-fades as you move.
  For TV apps that ship a banner, a blurred copy of the banner glows behind the top shelf.
- **Top row (dock):** your favourite apps. Names appear only on the focused card, and the row bleeds past the edge to hint there's more.
- **All Apps grid:** a grid of 16:10 cards, where each icon sits on a plate tinted with its own colour.
- **Top bar:** round cerulean glass buttons for Voice, Sound Studio, Cast, Screen, Wi-Fi, Bluetooth, Free RAM, Settings and Lumen, plus the clock.
  A focused button fills cerulean with a Neon Rose ring.

## The focus effect
1. The card **tilts in** from the direction focus came from, then springs flat with a slight overshoot.
2. It **lifts**: it scales to 1.12x and casts a real elevation shadow.
3. **Layered parallax:** the icon lags behind its plate, so the card looks like it has depth.
4. A **specular glare** sweeps across the card once.
5. While the card stays focused, it **breathes** with a slow drift of less than 3°.

Other motion:
- Scrolling glides with an inertial deceleration.
- The top shelf scrolls slower than the page and fades out.
- Cards rise in a staggered sequence on launch.
- Apps open with a zoom from their card.

## Why it stays fast on a Mali-G31
- **Cheap blur:** the aurora is painted into a tiny 96×54 bitmap and stretched to full screen by the GPU.
  The bilinear upscale itself acts as the blur. The light drifts at about 25 fps and stops completely when the launcher is hidden.
- **No redraws during motion:** every focus animation only changes scale, rotation, translation or alpha on the view.
  The card's pixels are never repainted; the GPU just transforms them.
- **Light on memory:** icons and banners are rendered once to small bitmaps on a background thread, and only one card animates at a time.
- **If it still feels slow:** open Lumen settings and set **Motion: Calm** and **Background: Pure black**.

## Controls
- **OK** opens the focused app.
- **MENU** (or hold OK) on an app lets you add it to the top row, move it, hide it, see app info or uninstall it.
- **MENU** anywhere else opens Lumen settings: background, motion, clock, stats, system apps, hidden apps and default home.
- **BACK** or **HOME** returns to the top.

## Install
- **Using a USB stick:** copy `Lumen.apk` to the stick, then open it with the projector's file manager.
- **Using ADB:** run `adb connect <projector-ip>:5555`, then `adb install -r Lumen.apk`.

## Make it the default home
Press **HOME** on the remote, choose **Lumen**, then choose **Always**.
If the stock launcher keeps coming back, run:
```
adb shell cmd package set-home-activity com.lumen.launcher/.HomeActivity
```
If it still won't stick, find the stock launcher and disable it. This is reversible.
```
adb shell cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME
adb shell pm disable-user --user 0 <stock.launcher.package>
```
To undo it, run `adb shell pm enable <stock.launcher.package>` or uninstall Lumen.

## Build from source
On Ubuntu, install the tools:
```
apt install aapt dalvik-exchange android-sdk-platform-23 apksigner zipalign default-jdk-headless
```
Then run `./build.sh` in `app/`.
- **Signing.** Without `lumen-release.keystore` the script makes a new key for you. Your build works on your projector, but can't update a Lumen installed from this repo's releases (Android only accepts updates signed with the same key). The official key is never stored in the repo.
- **API keys.** None are built in, ever. Your build uses the key service named in the update channel, or keys entered on the phone remote.
- **Tests.** `javac -cp build/classes -d /tmp/t test/ParserTest.java && java -cp /tmp/t:build/classes com.lumen.launcher.ParserTest`

## Design references
- [Apple HIG: Focus and selection](https://developer.apple.com/design/human-interface-guidelines/focus-and-selection):
  focus is shown through scale, lift and parallax; focus never moves on its own; the effect reverts fully when focus leaves.
- [Apple HIG: Top Shelf](https://developer.apple.com/design/human-interface-guidelines/top-shelf):
  a top-row app drives a large showcase area, its label appears on focus, and its fallback image is flipped and blurred.
- [tvOS guidelines summary](https://github.com/BasThomas/tvOS-guidelines):
  layered artwork, padding so focused items never clip, and partially visible rows to hint at more content.
- Open-source TV launchers such as [FLauncher](https://alternativeto.net/software/flauncher--android-tv/about/) and
  [LtvLauncher](https://ossinsight.io/analyze/LeanBitLab/LtvLauncher) prefer apps' own TV banners, which Lumen uses too.
