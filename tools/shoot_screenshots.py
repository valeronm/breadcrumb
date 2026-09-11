#!/usr/bin/env python3
"""Drive the demo build over adb and shoot the screenshots the README and the store use.

Writes two artifacts from one pass:

  tools/demo-data/screenshots/<name>.png   full-resolution raw captures (gitignored) — what
                                           generate_store_assets.py composites into store frames
  docs/screenshots/<name>.png              the committed README set (see the compression
                                           constants below)

Controls are found in the UI's accessibility tree (uiautomator dump) by label, each label read
from the app's default string table by resource name, so rewording a string needs no change
here. A renamed key fails naming the key.

The shoot runs on a Pixel 8 Pro emulator, prepared once per shoot; none of it is automated
because each step is either interactive (the file picker) or a mutation this script should not
decide on its own. With a phone also connected, export `ANDROID_SERIAL` naming the emulator:
adb refuses to pick between two devices, and `installDemo` installs on every one it sees.

  python3 tools/generate_demo_history.py
  ./gradlew :app:installDemo
  adb shell pm clear io.github.valeronm.breadcrumb.demo
  adb push tools/demo-data/demo-backup.json.gz /sdcard/Download/
  for p in ACCESS_FINE_LOCATION ACCESS_BACKGROUND_LOCATION ACTIVITY_RECOGNITION \
           POST_NOTIFICATIONS; do
    adb shell pm grant io.github.valeronm.breadcrumb.demo android.permission.$p
  done
  adb shell cmd deviceidle whitelist +io.github.valeronm.breadcrumb.demo

  # The tree is matched against the default `values/` table, which English renders; the PT
  # region gives km, a 24-hour clock and a decimal comma.
  adb shell cmd locale set-app-locales io.github.valeronm.breadcrumb.demo --locales en-PT
  adb shell cmd uimode night yes

  # Dynamic colour follows the wallpaper; with the system palette set to the seed named atop
  # ui/theme/SeededScheme.kt, it resolves to the scheme that file holds.
  adb shell "settings put secure theme_customization_overlay_packages \
    '{\"android.theme.customization.system_palette\":\"26805F\",
      \"android.theme.customization.accent_color\":\"26805F\",
      \"android.theme.customization.theme_style\":\"TONAL_SPOT\",
      \"android.theme.customization.color_source\":\"preset\"}'"

  adb shell settings put global sysui_demo_allowed 1
  demo() { adb shell am broadcast -a com.android.systemui.demo -e command "$@"; }
  demo enter
  # The timeline's trailing stay closes at the real clock.
  demo clock -e hhmm "$(adb shell date +%H%M | tr -d '\r')"
  demo battery -e level 100 -e plugged false
  demo network -e wifi show -e level 4 -e fully true
  demo network -e mobile hide
  demo notifications -e visible false

  # then in the app: flip Auto recording on, and restore the backup from the
  # Timeline's empty state (the picker is a system UI this script stays out of)

Usage:
  python3 tools/shoot_screenshots.py                 # shoot + compress
  python3 tools/shoot_screenshots.py --compress-only # redo docs/ from existing raws
  python3 tools/shoot_screenshots.py --map-wait 30   # slow network: give tiles longer
"""

import argparse
import re
import subprocess
import sys
import time
from pathlib import Path
from xml.etree import ElementTree

from PIL import Image

from demo_routes import OUT_DIR, REPO

RAW = OUT_DIR / "screenshots"
DOCS = REPO / "docs/screenshots"
APP = "io.github.valeronm.breadcrumb.demo"

WAIT_TIMEOUT = 10.0

# The committed set is for the README alone, which shows it at ~30% width.
# Octree keeps the track's colour ramp saturated among the basemap's greys.
DOCS_SCALE = 2
DOCS_COLORS = 256


def adb(*args, binary=False):
    r = subprocess.run(["adb", *args], capture_output=True, check=True)
    return r.stdout if binary else r.stdout.decode()


def ui_strings():
    """The default string table, {name: text} — the same `values/` the device renders."""
    table = {}
    for f in (REPO / "app/src/main/res/values").glob("strings_*.xml"):
        for s in ElementTree.parse(f).getroot().iter("string"):
            table[s.get("name")] = "".join(s.itertext())
    return table


STRINGS = ui_strings()


def res(name):
    """An anchored matcher for the control labelled by string resource [name]."""
    return "^" + re.escape(STRINGS[name]) + "$"


def wait(pattern):
    """Poll until a node whose text or description matches [pattern] is on screen; its centre."""
    deadline = time.monotonic() + WAIT_TIMEOUT
    while True:
        # uiautomator prints its own "dumped to" line after the XML on the same stream.
        raw = adb("exec-out", "uiautomator", "dump", "/dev/tty")
        tree = ElementTree.fromstring(raw[raw.index("<?xml"):raw.rindex(">") + 1])
        for node in tree.iter("node"):
            label = node.get("text") or node.get("content-desc") or ""
            if re.search(pattern, label):
                x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
                return (x1 + x2) // 2, (y1 + y2) // 2
        if time.monotonic() > deadline:
            sys.exit(f"gave up waiting for {pattern!r} — is the app on the expected screen?")
        time.sleep(1.0)


def tap(pattern):
    x, y = wait(pattern)
    adb("shell", "input", "tap", str(x), str(y))


def shoot(name, timeout=10.0):
    """Capture once two frames a second apart match, or as-is when [timeout] runs out.

    Basemap tiles keep arriving for as long as a cold cache takes; a blinking cursor or a
    stuck spinner never settles.
    """
    deadline = time.monotonic() + timeout
    last = None
    while True:
        frame = adb("exec-out", "screencap", "-p", binary=True)
        if frame == last or time.monotonic() > deadline:
            (RAW / name).write_bytes(frame)
            print(f"  {name}")
            return
        last = frame
        time.sleep(1.0)


def drive(map_wait):
    RAW.mkdir(parents=True, exist_ok=True)
    # An idle device sleeps between runs. Waking is safe to repeat; the keyguard dismiss
    # only clears an insecure lock, and a secured one surfaces as the first wait() failing.
    adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    adb("shell", "wm", "dismiss-keyguard")
    adb("shell", "am", "start", "-n", f"{APP}/io.github.valeronm.breadcrumb.ui.MainActivity")

    print("shooting:")
    # A resumed app sits on whichever tab the last run or the preparation left it.
    tap(res("nav_record"))
    # That row is the arming toggle, which the preparation sets and the shoot must not flip.
    wait(res("record_auto_recording"))
    shoot("record.png")

    tap(res("nav_timeline"))
    # A formatted row, not a resource value: the first drive on screen, today's or yesterday's.
    tap(r"^Driving · ")
    tap(res("color_mode_elevation"))
    shoot("track-detail.png", timeout=map_wait)
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    shoot("timeline.png")

    tap(res("nav_places"))
    tap(res("places_view_list"))
    tap(res("places_sort_most_visits"))
    shoot("places.png")

    tap(res("nav_insights"))
    shoot("journeys.png")
    tap(res("insights_tab_statistics"))
    shoot("statistics.png")


def compress():
    DOCS.mkdir(parents=True, exist_ok=True)
    print("compressing into docs/screenshots:")
    for raw in sorted(RAW.glob("*.png")):
        im = Image.open(raw)
        im = im.resize((im.width // DOCS_SCALE, im.height // DOCS_SCALE), Image.LANCZOS)
        im = im.convert("RGB").quantize(DOCS_COLORS, method=Image.Quantize.FASTOCTREE)
        out = DOCS / raw.name
        im.save(out, optimize=True)
        print(f"  {raw.name}: {raw.stat().st_size // 1024}K -> {out.stat().st_size // 1024}K")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--map-wait", type=float, default=45.0,
                    help="upper bound on waiting for the track map's tiles to settle")
    ap.add_argument("--compress-only", action="store_true",
                    help="skip the device; rebuild docs/screenshots from the existing raws")
    args = ap.parse_args()

    if not args.compress_only:
        drive(args.map_wait)
    compress()


if __name__ == "__main__":
    main()
