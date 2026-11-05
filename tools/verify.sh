#!/usr/bin/env bash
# Verify the island on the A79: reload SystemUI, post a real notification, screenshot,
# and measure the pill against the camera cutout instead of eyeballing it.
#
#   sh tools/verify.sh [--no-restart] [--tag TAG]
set -u

SERIAL="${SERIAL:-$(adb devices | sed -n 2p | cut -f1)}"
ADB="${ADB:-adb}"
PKG="io.github.lamin_sudo.halo"
OUT_DIR="${OUT_DIR:-${HALO_OUT:-$PWD/build/verify}}"
RESTART=1
[ "${1:-}" = "--no-restart" ] && RESTART=0

mkdir -p "$OUT_DIR"

command -v "$ADB" >/dev/null 2>&1 || ADB="$HOME/AppData/Local/Android/Sdk/platform-tools/adb"
"$ADB" devices | grep -q "$SERIAL" || { echo "device $SERIAL not attached"; exit 1; }

echo "== installed module =="
"$ADB" -s "$SERIAL" shell pm list packages | grep -q "$PKG" && echo "  $PKG present" || { echo "  not installed"; exit 1; }

echo "== LSPosed status =="
"$ADB" -s "$SERIAL" shell su -c '/data/adb/modules/zygisk_lsposed/lspctl status' 2>/dev/null | sed 's/^/  /'

if [ "$RESTART" = "1" ]; then
  echo "== restarting SystemUI to load hooks =="
  "$ADB" -s "$SERIAL" logcat -c
  "$ADB" -s "$SERIAL" shell su -c 'kill $(pidof com.android.systemui)' >/dev/null 2>&1
  sleep 6
fi

echo "== capturing baseline =="
"$ADB" -s "$SERIAL" exec-out screencap -p > "$OUT_DIR/verify_before.png" 2>/dev/null

echo "== posting test notification =="
"$ADB" -s "$SERIAL" shell cmd notification post -S bigtext -t 'Island verify' verifytag 'measuring the pill right now'

for i in 1 2 3; do
  "$ADB" -s "$SERIAL" exec-out screencap -p > "$OUT_DIR/verify_$i.png" 2>/dev/null
  sleep 1
done

echo "== logcat =="
"$ADB" -s "$SERIAL" logcat -d -s HaloIsland 2>/dev/null | tail -25 | sed 's/^/  /'

echo "== geometry check =="
python - "$OUT_DIR" "$SERIAL" <<'PY'
import sys, subprocess
from PIL import Image

out_dir, serial = sys.argv[1], sys.argv[2]

def adb(*a):
    return subprocess.run(["adb", "-s", serial, *a], capture_output=True, text=True).stdout

import re

cutout = None
for line in adb("shell", "dumpsys", "display").splitlines():
    if "cutout DisplayCutout" in line:
        # The insets Rect is (0, 107 - 0, 0); the hole itself is inside boundingRect={Bounds=[...]}.
        for m in re.finditer(r"Rect\((\d+), (\d+) - (\d+), (\d+)\)", line):
            x0, y0, x1, y1 = (int(v) for v in m.groups())
            if x1 - x0 > 0 and y1 - y0 > 0:
                cutout = (x0, y0, x1, y1)
                break
        break

print(f"  hole: {cutout}")

rows = range(0, 160)
try:
    before = Image.open(f"{out_dir}/verify_before.png").convert("L")
except Exception as e:
    print(f"  baseline unreadable ({e})")
    sys.exit(0)

for i in (1, 2, 3):
    path = f"{out_dir}/verify_{i}.png"
    try:
        after = Image.open(path).convert("L")
    except Exception as e:
        print(f"  frame {i}: unreadable ({e})")
        continue
    w, h = after.size
    a = after.load()
    b = before.load()

    # Columns in the status-bar band whose pixels changed: candidate island, whatever the
    # wallpaper colour is.
    changed, s = [], None
    for x in range(w):
        hit = any(abs(a[x, y] - b[x, y]) > 24 for y in rows)
        if hit and s is None: s = x
        if not hit and s is not None:
            if x - s > 24: changed.append((s, x - 1))
            s = None
    if s is not None and w - s > 24: changed.append((s, w - 1))

    if not changed:
        print(f"  frame {i}: nothing changed in the top band -> island did not show")
        continue

    for (x0, x1) in changed:
        # The island paints itself pure black; a notification icon appearing is not black.
        dark = sum(1 for x in range(x0, x1 + 1) for y in rows if a[x, y] < 12)
        total = (x1 - x0 + 1) * len(list(rows))
        is_pill = dark / max(total, 1) > 0.5
        centre = (x0 + x1) / 2
        width = x1 - x0 + 1
        if not is_pill:
            print(f"  frame {i}: x={x0}..{x1} w={width} changed but not a black pill (icon/clock)")
            continue
        if cutout:
            covers = x0 <= cutout[0] and x1 >= cutout[2]
            off = abs(centre - (cutout[0] + cutout[2]) / 2)
            verdict = "covers hole" if covers else "HOLE NOT COVERED"
            print(f"  frame {i}: PILL x={x0}..{x1} w={width} centre={centre:.0f} "
                  f"(hole {cutout[0]}..{cutout[2]}, off {off:.1f}px) -> {verdict}")
        else:
            print(f"  frame {i}: PILL x={x0}..{x1} w={width}")
PY

echo
echo "frames: $OUT_DIR/verify_1.png .. verify_3.png"
