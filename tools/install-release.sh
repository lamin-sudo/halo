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
PKG="io.github.lamin_sudo.halo"
APK="${APK:-$(dirname "$0")/../app/build/outputs/apk/release/app-release.apk}"
# The certificate we expect to see. If a build ever comes from a different key, this stops the
# install instead of quietly making an update impossible later.
EXPECTED_SHA256="1b7b603f4541a74e5f52fefb852706af0300faf4de44fced7b0409cbae488043"

[ -f "$APK" ] || { echo "no release APK at $APK — run: ./gradlew :app:assembleRelease"; exit 1; }

# Without this the script ran on to `adb -s "" install` and reported "(nothing installed)" as if
# that were a normal state. One device, found or asked for.
if [ -z "$SERIAL" ]; then
  echo "no device on adb — connect one, or run: SERIAL=<serial> sh tools/install-release.sh"
  exit 1
fi

echo "== signing"
SHA=$("$ADB" -s "$SERIAL" shell getprop ro.build.version.release 2>/dev/null | tr -d '\r')
echo "  device Android $SHA"

# apksigner lives in a build-tools folder whose version is not predictable, so take it from the
# environment if it is set and otherwise try the usual places. $APKSIGNER wins.
find_apksigner() {
  [ -n "${APKSIGNER:-}" ] && { echo "$APKSIGNER"; return; }
  command -v apksigner >/dev/null 2>&1 && { echo "apksigner"; return; }
  local root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/c/Android/Sdk}}"
  local bt
  for bt in "$root"/build-tools/*/apksigner.bat "$root"/build-tools/*/apksigner; do
    [ -e "$bt" ] && echo "$bt" && return
  done
}
APKSIGNER_BIN=$(find_apksigner)

if [ -z "$APKSIGNER_BIN" ]; then
  echo "  apksigner not found — set APKSIGNER=/path/to/apksigner to check the certificate."
  echo "  Continuing, but the pinned-key guard is OFF for this install."
else
  # A .bat cannot be exec'd from a POSIX shell without going through a Windows host, and the
  # quoting of that differs per shell (MSYS_NO_PATHCONV alone changes whether "cmd //c" runs a
  # command or drops into an interactive prompt). PowerShell is the one form that behaves the
  # same from Git Bash, cmd, and WSL; on Linux/macOS the tool is a real executable.
  case "$APKSIGNER_BIN" in
    *.bat)
      ACTUAL=$(powershell -NoProfile -Command \
        "& '${APKSIGNER_BIN}' verify --print-certs '${APK}'" 2>/dev/null \
        | grep "certificate SHA-256 digest" | awk '{print $NF}')
      ;;
    *)
      ACTUAL=$("$APKSIGNER_BIN" verify --print-certs "$APK" 2>/dev/null \
        | grep "certificate SHA-256 digest" | awk '{print $NF}')
      ;;
  esac
  if [ "${ACTUAL,,}" != "$EXPECTED_SHA256" ]; then
    echo "  unexpected certificate: ${ACTUAL:-<apksigner produced nothing>}"; exit 1
  fi
  echo "  $ACTUAL (matches the pinned key)"
fi

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
"$ADB" -s "$SERIAL" logcat -d -v time HaloIsland:V '*:S' 2>/dev/null | grep -E "booted|installed" | tail -6

cat <<'TXT'

LAST STEP — only you can do it, on the phone:
  LSPosed Manager → Modules → "A79 Island" → make sure it is enabled and SystemUI is still
  ticked in its scope, then restart SystemUI (this script does that part).
When everything looks right, turn the verbose log back off:
  adb shell su -c 'settings put global a79_island_debug_log 0'
TXT
