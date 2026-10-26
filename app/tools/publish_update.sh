#!/usr/bin/env bash
# Publishes a Lumen version to the update channel in this repo (ota/), so projectors pick it up.
#
#   tools/publish_update.sh "What's new in this version"
#
# Before running: raise android:versionCode and android:versionName in app/AndroidManifest.xml,
# and have the release key (app/lumen-release.keystore, never committed) in place.
# Builds never carry API keys (see proxy/ for Lumen's key service).
set -euo pipefail
cd "$(dirname "$0")/.."            # app/
ROOT=$(cd .. && pwd)
REPO=$(sed -n 's/.*REPO = "\([^"]*\)".*/\1/p' src/com/lumen/launcher/LumenConfig.java | head -1)

NOTES=${1:-""}
CODE=$(sed -n 's/.*android:versionCode="\([0-9]*\)".*/\1/p' AndroidManifest.xml | head -1)
NAME=$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' AndroidManifest.xml | head -1)
[ -n "$CODE" ] && [ -n "$NAME" ] || { echo "Can't read the version from AndroidManifest.xml"; exit 1; }
[ -f lumen-release.keystore ] || [ -n "${LUMEN_KEYSTORE:-}" ] || { echo "The release key (lumen-release.keystore) isn't here. Builds signed with another key can't update installed projectors."; exit 1; }

OLD=$(python3 -c "import json;print(json.load(open('$ROOT/ota/update.json'))['versionCode'])" 2>/dev/null || echo 0)
[ "$CODE" -gt "$OLD" ] || { echo "versionCode $CODE isn't higher than the published $OLD. Raise it first."; exit 1; }

./build.sh
if unzip -p Lumen.apk classes.dex | grep -qaE "AIza[0-9A-Za-z_-]{30}|eyJhbGciOi"; then echo "This build contains an API key. Not publishing."; exit 1; fi

mkdir -p "$ROOT/ota"
cp Lumen.apk "$ROOT/ota/Lumen.apk"
SHA=$(sha256sum "$ROOT/ota/Lumen.apk" | cut -c1-64)
SIZE=$(stat -c %s "$ROOT/ota/Lumen.apk")
URL="https://raw.githubusercontent.com/$REPO/main/ota/Lumen.apk"

# keep the channel's key-service address
PROXY=$(python3 -c "import json;print(json.load(open('$ROOT/ota/update.json')).get('keyProxy',''))" 2>/dev/null || true)
python3 - "$CODE" "$NAME" "$URL" "$SHA" "$SIZE" "$NOTES" "$PROXY" > "$ROOT/ota/update.json" <<'PY'
import json, sys
code, name, url, sha, size, notes, proxy = sys.argv[1:8]
print(json.dumps({"versionCode": int(code), "versionName": name, "url": url, "sha256": sha,
                  "size": int(size), "notes": notes, "keyProxy": proxy}, indent=2))
PY

echo "Lumen $NAME (code $CODE) · $SIZE bytes · sha256 $SHA"
cd "$ROOT"
git add ota/Lumen.apk ota/update.json app/AndroidManifest.xml
git commit -q -m "Lumen $NAME" -m "${NOTES:-Lumen $NAME}"
echo "Committed. Push to main (git push) and projectors offer Lumen $NAME at their next check."
