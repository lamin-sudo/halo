#!/usr/bin/env bash
# Move the phone from the debug build to the signed release, and put the system back the way it
# was afterwards.
#
#   sh tools/install-release.sh [--no-restart]
#
# Why this needs a hand: the release APK is signed with a different key than the debug one, so
# Android refuses an in-place update. The old package has to go, and uninstalling it clears
# `pm grant` and can leave LSPosed's per-module state stale. Everything that can be scripted is
# done here; the one thing nobody can script is the LSPosed switch itself (see the last line).
set -eu

SERIAL="${SERIAL:-$(adb devices | sed -n 2p | cut -f1)}"
ADB="${ADB:-/c/Android/Sdk/platform-tools/adb}"
PKG="dz.island.a79"
APK="${APK:-$(dirname "$0")/../app/build/outputs/apk/release/app-release.apk}"
# The certificate we expect to see. If a build ever comes from a different key, this stops the
# install instead of quietly making an update impossible later.
EXPECTED_SHA256="1b7b603f4541a74e5f52fefb852706af0300faf4de44fced7b0409cbae488043"

[ -f "$APK" ] || { echo "no release APK at $APK — run: ./gradlew :app:assembleRelease"; exit 1; }

echo "== signing"
SHA=$("$ADB" -s "$SERIAL" shell getprop ro.build.version.release 2>/dev/null | tr -d '\r')
echo "  device Android $SHA"
ACTUAL=$(cmd //c "C:\\Android\\Sdk\\build-tools\\34.0.0\\apksigner.bat verify --print-certs ${APK//\//\\}" 2>/dev/null \
  | grep "certificate SHA-256 digest" | awk '{print $NF}')
if [ "${ACTUAL,,}" != "$EXPECTED_SHA256" ]; then
  echo "  unexpected certificate: $ACTUAL"; exit 1
fi
echo "  $ACTUAL (matches the pinned key)"

echo "== replacing the debug build"
"$ADB" -s "$SERIAL" uninstall "$PKG" >/dev/null 2>&1 || echo "  (nothing installed)"
"$ADB" -s "$SERIAL" install "$APK" 2>&1 | tail -1

echo "== re-granting what the app needs"
"$ADB" -s "$SERIAL" shell su -c "pm grant $PKG android.permission.WRITE_SECURE_SETTINGS"
"$ADB" -s "$SERIAL" shell su -c "pm grant $PKG android.permission.POST_NOTIFICATIONS"
# The release build is silent by design (BuildConfig.DEBUG is false), so without this there is
# nothing to read while the first release boot is being checked.
"$ADB" -s "$SERIAL" shell su -c "settings put global a79_island_debug_log 1"
echo "  settings + verbose log on"

echo "== restart SystemUI to load the module"
"$ADB" -s "$SERIAL" shell "su -c 'kill \$(pidof com.android.systemui)'"
sleep 14
"$ADB" -s "$SERIAL" logcat -d -v time A79Island:V '*:S' 2>/dev/null | grep -E "booted|installed" | tail -6

cat <<'TXT'

LAST STEP — only you can do it, on the phone:
  LSPosed Manager → Modules → "A79 Island" → make sure it is enabled and SystemUI is still
  ticked in its scope, then restart SystemUI (this script does that part).
When everything looks right, turn the verbose log back off:
  adb shell su -c 'settings put global a79_island_debug_log 0'
TXT
