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
# The order matters and was learned the hard way: open the backdrop FIRST, then post. Posting
# first and opening the backdrop afterwards either lets the capsule expire during the second
# launch, or — for the media states — buries the player under a settings page. With singleTop on
# the activity, `am start --es test X` while the page is already on top is delivered to
# onNewIntent and still posts.
#
# Prerequisite: the module must be enabled in LSPosed with SystemUI in its scope. An updated APK is
# not always picked up by the running SystemUI — if nothing appears, reboot once.
set -eu

SERIAL="${SERIAL:-$(adb devices | sed -n 2p | cut -f1)}"
ADB="${ADB:-adb}"
PKG="io.github.lamin_sudo.halo"
OUT="${1:-$(dirname "$0")/../docs/images}"
BAND="60,0,960,760"          # the top of a 1080x2400 panel, where the island lives
[ -n "$SERIAL" ] || { echo "no device on adb"; exit 1; }
mkdir -p "$OUT"

shot() {  # shot <file-name> [band] — top band of the panel, cropped when Pillow is available
  local name="$1" band="${2:-$BAND}"
  "$ADB" -s "$SERIAL" exec-out screencap -p > "$OUT/$name.png"
  python - "$OUT/$name.png" "$band" <<'PY' 2>/dev/null || echo "  (uncropped: no Pillow)"
import sys
from PIL import Image
path, band = sys.argv[1], tuple(int(x) for x in sys.argv[2].split(','))
im = Image.open(path)
im.crop(band if im.width > 1000 else (0, 0, im.width, min(760, im.height))).save(path)
PY
  echo "  $name.png"
}

full() {  # full <file-name> — the whole panel, for the settings screen and the launcher
  "$ADB" -s "$SERIAL" exec-out screencap -p > "$OUT/$1.png"
  echo "  $1.png (full panel)"
}

start() {  # start <extra args…> — one `am start` on our activity
  "$ADB" -s "$SERIAL" shell "am start -n $PKG/.SettingsActivity $1" >/dev/null 2>&1
}

clear() { start "--es test stop"; sleep 2; }

# A clean surface under the island. The launcher would show someone's real app icons and folders
# around the card; the app's own settings page is dark, branded, and says nothing about whoever
# took the shot.
backdrop() { start ""; sleep "${1:-2}"; }

post()   { start "--es test $1"; sleep "${2:-2}"; }

echo "== waking the screen"
"$ADB" -s "$SERIAL" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
"$ADB" -s "$SERIAL" shell wm dismiss-keyguard >/dev/null 2>&1
sleep 1

# Silence every other app for the whole run. Without this, a real message that happens to arrive
# while the synthetic burst is on screen wins the slot — and the screenshot ends up holding
# somebody's chats, contact names and group links. That has happened, and it is the reason this
# line exists. DND is restored at the end, whatever it was.
DND_BEFORE=$("$ADB" -s "$SERIAL" shell settings get global zen_mode 2>/dev/null | tr -d '\r')
[ "$DND_BEFORE" = "null" ] && DND_BEFORE=0
"$ADB" -s "$SERIAL" shell "cmd notification set_dnd on" >/dev/null 2>&1
echo "  do-not-disturb on (was: zen_mode=$DND_BEFORE)"
restore_dnd() {
  if [ "$DND_BEFORE" = "0" ] || [ -z "$DND_BEFORE" ]; then
    "$ADB" -s "$SERIAL" shell "cmd notification set_dnd off" >/dev/null 2>&1
    echo "  do-not-disturb restored: off"
  else
    "$ADB" -s "$SERIAL" shell "cmd notification set_dnd priority" >/dev/null 2>&1
    echo "  do-not-disturb was already on, left on"
  fi
}
trap restore_dnd EXIT

# Prove the module is running before writing a single file. Every screenshot taken while it is
# asleep looks like a perfect dark wallpaper, and that is the one failure worth refusing on.
"$ADB" -s "$SERIAL" logcat -c >/dev/null 2>&1
clear; backdrop 1; post 1 3
if ! "$ADB" -s "$SERIAL" logcat -d -s HaloIsland 2>/dev/null | grep -q "show NOTIFICATION"; then
  cat <<MISSING
The module did not answer, so nothing was captured. Check in this order:
  1. LSPosed -> Modules -> Halo: enabled, and SystemUI ticked in its scope.
  2. Reboot — an updated APK is not always picked up by the running SystemUI:
       adb reboot && sleep 60 && sh tools/capture-gallery.sh
  3. If it still says nothing: adb shell su -c 'settings get global a79_island_disabled'
MISSING
  exit 1
fi
echo "  module answered, capturing"

echo "== capsule (one message)"
clear; backdrop; post 1; shot capsule

echo "== list card (four rows inside the same 3s grouping window)"
clear; backdrop
for i in 1 2 3 4; do post chat 0.7; done
# The burst collapses to a "4 messages" pill as soon as it settles — which is correct behaviour,
# and the wrong picture. Tapping the pill is how a person opens the list, so it is how the
# screenshot is taken. Confirmed by the log: `list opened (4 rows)`.
sleep 1
"$ADB" -s "$SERIAL" shell input tap 540 120 >/dev/null 2>&1
sleep 2; shot list-card

echo "== detail card (paragraph + the row's own action chips)"
clear; backdrop; post detail
"$ADB" -s "$SERIAL" shell input tap 540 120 >/dev/null 2>&1   # expand the capsule
sleep 2; shot detail-card

echo "== download progress"
clear; backdrop
for i in 1 2 3; do post progress 1.2; done
shot download

echo "== incoming call"
clear; backdrop; post call 3; shot call-card

echo "== media: a real session, not a fake one"
# A synthetic media row gives a capsule with no artwork. The player card is the prettiest thing
# the module draws, so it gets a real YouTube session: artwork, a title that exists, and a
# progress line that moves. Nothing personal is on screen — a public music video.
clear
"$ADB" -s "$SERIAL" shell "am start -a android.intent.action.VIEW -d 'https://www.youtube.com/watch?v=9bZkp7q19f0' com.google.android.youtube" >/dev/null 2>&1
sleep 16
# Pull the video out of the way: the island sits at the top of the screen either way, and a plain
# backdrop reads better in a README than a paused frame.
"$ADB" -s "$SERIAL" shell input keyevent KEYCODE_HOME >/dev/null 2>&1
sleep 2; backdrop 2; shot media-capsule
"$ADB" -s "$SERIAL" shell input tap 540 120 >/dev/null 2>&1   # expand into the player card
sleep 2; shot player-card
"$ADB" -s "$SERIAL" shell input swipe 540 260 540 90 220 >/dev/null 2>&1  # close it: the ring stays
sleep 3; shot media-ring "280,20,800,300"
"$ADB" -s "$SERIAL" shell "am force-stop com.google.android.youtube" >/dev/null 2>&1

echo "== settings screen and launcher icon"
backdrop 3; full settings
"$ADB" -s "$SERIAL" shell input keyevent KEYCODE_HOME >/dev/null 2>&1
sleep 2; full launcher

cat <<TXT

Done: $OUT
Look at them before committing. If a card is missing from a shot it is almost always one of two
things: the module was not loaded (the guard above catches that), or the state expired while the
backdrop was being opened (the TTL is a79_island_ttl_ms, 12s on this phone).
TXT
