#!/usr/bin/env bash
# Builds Lumen.apk with plain SDK tools (aapt, javac, dx, zipalign, apksigner). No Gradle.
set -euo pipefail
cd "$(dirname "$0")"

ANDROID_JAR=${ANDROID_JAR:-/usr/lib/android-sdk/platforms/android-23/android.jar}
OUT=build
rm -rf "$OUT" && mkdir -p "$OUT/gen" "$OUT/classes"

echo "1/6 resources"
aapt package -f -m -J "$OUT/gen" -M AndroidManifest.xml -S res -A assets -I "$ANDROID_JAR" \
     --min-sdk-version 23 --target-sdk-version 30 \
     -F "$OUT/unsigned.apk"

echo "2/6 compile"
# No API keys are ever built in: Lumen uses your own keys (phone remote) or its key service (proxy/).
# API 28 stubs: compile-only, never packaged (the real classes come from the device)
mkdir -p "$OUT/stubs"
javac -nowarn -Xlint:-options -encoding UTF-8 -source 8 -target 8 \
      -bootclasspath "$ANDROID_JAR" -d "$OUT/stubs" $(find stubs -name '*.java')
javac -nowarn -Xlint:-options -encoding UTF-8 -source 8 -target 8 \
      -bootclasspath "$ANDROID_JAR" -classpath "$OUT/stubs" -d "$OUT/classes" \
      $(find src "$OUT/gen" -name '*.java')

echo "3/6 dex"
dalvik-exchange --dex --min-sdk-version=23 --output="$OUT/classes.dex" "$OUT/classes"

echo "4/6 package"
( cd "$OUT" && aapt add -f unsigned.apk classes.dex >/dev/null )

echo "5/6 align"
zipalign -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "6/6 sign"
# The official key is kept outside the repo. Without it you get your own key: fine for your projector,
# but such a build can't update a Lumen installed from the official releases (and vice versa).
KS=${LUMEN_KEYSTORE:-lumen-release.keystore}
KP=${LUMEN_KS_PASS:-lumenpass}
if [ ! -f "$KS" ]; then
  echo "   no $KS: creating a new signing key (keep it to sign your future builds)"
  keytool -genkeypair -keystore "$KS" -storepass "$KP" -keypass "$KP" \
          -alias lumen -keyalg RSA -keysize 2048 -validity 10000 \
          -dname "CN=Lumen Launcher" >/dev/null 2>&1
fi
apksigner sign --ks "$KS" --ks-pass "pass:$KP" --key-pass "pass:$KP" \
          --ks-key-alias lumen --min-sdk-version 23 \
          --out Lumen.apk "$OUT/aligned.apk"
echo "built: $(pwd)/Lumen.apk ($(du -h Lumen.apk | cut -f1))"
