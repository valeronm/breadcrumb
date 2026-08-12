#!/usr/bin/env python3
"""Drive the demo build over adb and shoot the six screenshots the README and the store use.

Writes two artifacts from one pass:

  tools/demo-data/screenshots/<name>.png   full-resolution raw captures (gitignored) — what
                                           generate_store_assets.py composites into store frames
  docs/screenshots/<name>.png              the committed README set (see the compression
                                           constants below)

The captures are found by reading the UI's own accessibility tree (uiautomator dump) and
tapping controls by their labels, with each label read out of the app's default string table
rather than spelled here — so a reworded control moves the shoot with it, and a renamed key
fails loudly naming the key. Coordinates appear nowhere.

Before running, the phone must be prepared once per shoot; none of it is automated because
each step is either interactive (the file picker) or a mutation this script should not
decide on its own:

  python3 tools/generate_demo_history.py
  ./gradlew :app:installDemo
  adb shell pm clear io.github.valeronm.breadcrumb.demo
  adb push tools/demo-data/demo-backup.json.gz /sdcard/Download/
  for p in ACCESS_FINE_LOCATION ACCESS_BACKGROUND_LOCATION ACTIVITY_RECOGNITION \
           POST_NOTIFICATIONS; do
    adb shell pm grant io.github.valeronm.breadcrumb.demo android.permission.$p
  done
  adb shell cmd deviceidle whitelist +io.github.valeronm.breadcrumb.demo
  # then in the app: flip Auto recording on, and restore the backup from the
  # Timeline's empty state (the picker is a system UI this script stays out of)

The device stays English for the shoot — the tree is matched against the default `values/`
table, which is what an English device renders. Screen awake and unlocked; the status bar
shows whatever it shows (shoot in the morning on a full battery for a clean one).

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

# The one name generate_store_assets.py imports: the seam between the shoot (producer) and the
# store frames (consumer), stated once so the two cannot glob different folders.
RAW = OUT_DIR / "screenshots"
DOCS = REPO / "docs/screenshots"
APP = "io.github.valeronm.breadcrumb.demo"

WAIT_TIMEOUT = 10.0

# The committed set is palette PNG at half size: the README shows ~30% width, so half
# resolution still overshoots what is displayed, and 256 colours with dithering survive
# the map's ramps. The store pipeline never sees these — it reads RAW.
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
        # Dumped straight to the stream — no /sdcard file, one adb call; the XML is followed
        # by uiautomator's own "dumped to" line, so it is cut at the document's last '>'.
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
    """Capture once the screen holds still — two matching frames a second apart.

    Stability, not a fixed sleep, because the slowest screen is waiting on network: basemap
    tiles trickle in for however long a cold cache takes, and a sleep long enough for that
    wastes its whole length everywhere else. [timeout] bounds a screen that never settles
    (a blinking cursor, a stuck spinner) — the shot is then taken as-is.
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
    # A phone on the charger drifts into its screensaver between runs. Waking is safe to
    # repeat; the keyguard dismiss only clears an insecure lock, and a secured one surfaces
    # as the first wait() failing — with the phone in hand being the fix.
    adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    adb("shell", "wm", "dismiss-keyguard")
    adb("shell", "am", "start", "-n", f"{APP}/io.github.valeronm.breadcrumb.ui.MainActivity")

    print("shooting:")
    # Stated, not assumed: a resumed app sits on whichever tab the last run (or the person
    # preparing the phone) left it.
    tap(res("nav_record"))
    # Waited for, never tapped: that row is the arming toggle, and the shoot must not
    # flip what the person preparing the phone set.
    wait(res("record_auto_recording"))
    shoot("record.png")

    tap(res("nav_timeline"))
    # A formatted row, not a resource value: the first trip row on screen — yesterday's day
    # is fully derived, today's may be empty.
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
        im = im.convert("RGB").quantize(DOCS_COLORS, method=Image.Quantize.MEDIANCUT)
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
