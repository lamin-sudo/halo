#!/usr/bin/env bash
# Regenerate the screenshots the README shows, from the app's own synthetic test states.
#
#   sh tools/capture-gallery.sh [outdir]
#
# Why a script and not a folder of saved images: every picture in docs/images/ has to be
# reproducible from a clean device, and none of them may contain anyone's real messages. The
# states come from SettingsActivity's `--es test <variant>` entries, which post synthetic
# notifications from this app, so what you see in the gallery is the island, not someone's chat.
#
# Prerequisite: the module must be enabled in LSPosed with SystemUI in its scope, otherwise every
# shot comes out as a plain screen.
set -eu

SERIAL="${SERIAL:-$(adb devices | sed -n 2p | cut -f1)}"
ADB="${ADB:-adb}"
PKG="io.github.lamin_sudo.halo"
OUT="${1:-$(dirname "$0")/../docs/images}"
BAND="60,0,960,760"          # the top of a 1080x2400 panel, where the island lives
[ -n "$SERIAL" ] || { echo "no device on adb"; exit 1; }
mkdir -p "$OUT"

shot() {  # shot <file-name> — full panel, then cropped when Pillow is available
  local name="$1"
  "$ADB" -s "$SERIAL" exec-out screencap -p > "$OUT/$name.png"
  python - "$OUT/$name.png" "$BAND" <<'PY' 2>/dev/null || echo "  (uncropped: no Pillow)"
import sys
from PIL import Image
path, band = sys.argv[1], tuple(int(x) for x in sys.argv[2].split(','))
im = Image.open(path)
im.crop(band if im.width > 1000 else (0, 0, im.width, min(760, im.height))).save(path)
PY
  echo "  $name.png"
}

fire() {  # fire <test variant> <seconds to wait>
  "$ADB" -s "$SERIAL" shell "am start -n $PKG/.SettingsActivity --es test $1" >/dev/null 2>&1
  sleep "${2:-3}"
  # Then put a clean backdrop under the island. Without this the shot keeps whatever was on
  # screen — the launcher, with someone's real app icons and folders visible around the card.
  # The app's own settings page is dark, branded, and says nothing about whoever took the shot.
  "$ADB" -s "$SERIAL" shell "am start -n $PKG/.SettingsActivity" >/dev/null 2>&1
  sleep 1
}

echo "== waking the screen and clearing any old rows"
"$ADB" -s "$SERIAL" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
"$ADB" -s "$SERIAL" shell wm dismiss-keyguard >/dev/null 2>&1
"$ADB" -s "$SERIAL" shell "am start -n $PKG/.SettingsActivity --es test stop" >/dev/null 2>&1
sleep 2

# Prove the module is actually running before producing eight pictures of an empty wallpaper.
# Observed on this device: after `adb install -r` of a rebuilt release plus a SystemUI restart,
# the module was registered, enabled and scoped in LSPosed's own database, yet SystemUI logged
# nothing at all and no island appeared. A reboot is the usual cure; this check at least stops
# the script from shipping blank images while that is happening.
"$ADB" -s "$SERIAL" logcat -c >/dev/null 2>&1
"$ADB" -s "$SERIAL" shell "am start -n $PKG/.SettingsActivity --es test 1" >/dev/null 2>&1
sleep 3
if ! "$ADB" -s "$SERIAL" logcat -d -s HaloIsland 2>/dev/null | grep -q "show NOTIFICATION"; then
  cat <<MISSING
The module did not answer, so nothing was captured. Check in this order:
  1. LSPosed -> Modules -> Halo: enabled, and SystemUI ticked in its scope.
  2. Reboot (an updated APK is not always picked up by the running SystemUI):
       adb reboot && sleep 60 && sh tools/capture-gallery.sh
  3. If it still says nothing: adb shell su -c 'settings get global a79_island_disabled'
MISSING
  exit 1
fi
echo "  module answered, capturing"

echo "== capsule (one message)"
fire 1 3;                 shot capsule

echo "== list card (a burst, four rows)"
for i in 1 2 3 4; do fire chat 1; done
sleep 1;                  shot list-card

echo "== detail card (paragraph + the row's own action chips)"
"$ADB" -s "$SERIAL" shell "am start -n $PKG/.SettingsActivity --es test stop" >/dev/null 2>&1
sleep 1; fire detail 2
"$ADB" -s "$SERIAL" shell input tap 540 120 >/dev/null 2>&1   # expand the capsule
sleep 2;                  shot detail-card

echo "== download progress"
"$ADB" -s "$SERIAL" shell "am start -n $PKG/.SettingsActivity --es test stop" >/dev/null 2>&1
sleep 1
for i in 1 2 3; do fire progress 2; done;   shot download

echo "== incoming call"
"$ADB" -s "$SERIAL" shell "am start -n $PKG/.SettingsActivity --es test stop" >/dev/null 2>&1
sleep 1; fire call 3;                       shot call-card

echo "== media ring (nothing expanded, session live)"
"$ADB" -s "$SERIAL" shell "am start -n $PKG/.SettingsActivity --es test stop" >/dev/null 2>&1
sleep 1; fire media 4;                      shot media-ring

echo "== settings screen and launcher icon"
"$ADB" -s "$SERIAL" shell "am start -n $PKG/.SettingsActivity" >/dev/null 2>&1
sleep 3; "$ADB" -s "$SERIAL" exec-out screencap -p > "$OUT/settings.png"; echo "  settings.png (full panel)"
"$ADB" -s "$SERIAL" shell input keyevent KEYCODE_HOME >/dev/null 2>&1
sleep 2; "$ADB" -s "$SERIAL" exec-out screencap -p > "$OUT/launcher.png"; echo "  launcher.png (full panel)"

cat <<TXT

Done: $OUT
Check them before committing — a shot taken while the module was disabled looks identical to a
black screen with a status bar, and that is the one failure this script cannot detect for you.
TXT
