#!/usr/bin/env bash
# Measure what the island actually costs, instead of guessing.
#
#   sh tools/battery-soak.sh [minutes]        (default 30)
#
# It runs two equal idle windows on the unplugged phone and compares the drain:
#
#   A. the module off  (a79_island_disabled=1)  — the baseline
#   B. the module on, with a media session playing, so the ambient ring is sweeping
#
# The ring is the thing worth measuring: it is a 2.6s ValueAnimator that redraws the overlay every
# frame while music plays, and an overlay at window type 2021 keeps the compositor awake. If B is
# meaningfully worse than A, the ring gets a screen-off pause before anything else that idles
# (weather, step count) is even discussed.
#
# Screen off and no notifications is the point — start it, put the phone down, come back.
set -eu

SERIAL="${SERIAL:-$(adb devices | sed -n 2p | cut -f1)}"
ADB="${ADB:-/c/Android/Sdk/platform-tools/adb}"
MINUTES="${1:-30}"
OUT="${OUT:-${HALO_OUT:-$PWD/build/soak}}"
mkdir -p "$OUT"

level() { "$ADB" -s "$SERIAL" shell dumpsys battery 2>/dev/null | grep -m1 "level" | tr -d ' \r'; }
status() { "$ADB" -s "$SERIAL" shell dumpsys batterystats 2>/dev/null | grep -m1 "Estimated power use"; }

reset_and_note() { # $1 = label
  local t0; t0=$(date +%s)
  "$ADB" -s "$SERIAL" shell su -c 'dumpsys batterystats --reset' >/dev/null
  echo "[$1] start  $(date +%H:%M:%S)  $(level)" | tee -a "$OUT/$1.txt"
  sleep "$((MINUTES * 60))"
  local mins=$(( ($(date +%s) - t0) / 60 ))
  echo "[$1] end    $(date +%H:%M:%S)  $(level)  after ${mins}min" | tee -a "$OUT/$1.txt"
  "$ADB" -s "$SERIAL" shell dumpsys batterystats > "$OUT/$1-batterystats.txt" 2>/dev/null
  echo "[$1] $(status)" | tee -a "$OUT/$1.txt"
}

echo "== window A: module disabled"
"$ADB" -s "$SERIAL" shell su -c 'settings put global a79_island_disabled 1'
"$ADB" -s "$SERIAL" shell input keyevent KEYCODE_SLEEP
reset_and_note A

echo "== window B: module enabled, ring sweeping"
"$ADB" -s "$SERIAL" shell su -c 'settings put global a79_island_disabled 0'
"$ADB" -s "$SERIAL" shell monkey -p com.google.android.youtube -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
echo "  play something, then open the notification shade and leave the screen off"
read -r -p "  press ENTER once it is playing and the screen is off" _
reset_and_note B

echo
echo "== result"
paste <(grep -h "" "$OUT/A.txt") <(grep -h "" "$OUT/B.txt") 2>/dev/null || true
echo
echo "Restore the screen: adb shell input keyevent KEYCODE_WAKEUP"
echo "Kill switch if B looks bad: adb shell su -c 'settings put global a79_island_disabled 1'"
