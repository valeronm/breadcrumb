#!/usr/bin/env python3
"""Generate synthetic demo GPX tracks — the fixture for exercising GPX import by hand.

For screenshots, use generate_demo_history.py instead: it writes a whole demo *history* as a
backup file, carrying the named places, categories and per-fix quality metrics that no GPX file
can. These tracks remain the way to drive the import path itself, and to populate a device that
has nothing to restore from.

Produces three chained Coimbra tracks in tools/demo-data/:
  demo-drive.gpx   Work -> Home             (the evening before)
  demo-walk.gpx    Home -> loop -> Home      (morning)
  demo-bike.gpx    Home -> the Mondego       (midday)

The tracks deliberately chain through a common "Home" point so Breadcrumb's
stay-deriver reads the gaps between them as one place ("Stayed at Home")
instead of "Moved without recording" — which is what makes an import land as a
readable day rather than a scatter of orphans. Routes are resampled to uniform
spacing with smooth timing so the speed chart has no fake spikes.

NEVER use real personal tracks for store screenshots; use generated data.

Usage:
  python3 tools/generate_demo_tracks.py            # regenerate from routers
  # then import the .gpx into a clean install to exercise the import path.

Routes are fetched from public OSRM routers at run time, so two runs differ in
their geometry; pass --offline to keep whatever is already in tools/demo-data/.
Nothing there is committed — this script is what the repository keeps, and the
files are gitignored, so run it when you need them.
"""

import random
import sys

from demo_routes import (
    BIKE, CAR, FOOT, HOME, MARKET, OUT_DIR, RIVER, WORK, fetch_route, meters, resample, wander,
)

OUT = OUT_DIR

# Walk and bike land on this day, the drive on the day before. Deliberately older than the demo
# history in demo-backup.json.gz: an import is refused where an existing track already covers the
# period, so a fixture dated inside that history could not be imported into the same install.
BASE_DAY = "2025-05-14"

# (name, gpx type, router, waypoints, m/s, start HH:MM:SS on which day, resample step m)
TRACKS = [
    ("Drive home", "driving", CAR,
     [WORK, HOME], 9.5, ("prev", "18:40:00"), 18, "demo-drive.gpx"),
    ("Morning walk", "walking", FOOT,
     [HOME, MARKET, (-8.4085, 40.2100), HOME], 1.35, ("day", "08:12:00"), 10, "demo-walk.gpx"),
    ("Ride to the Mondego", "cycling", BIKE,
     [HOME, RIVER], 4.6, ("day", "11:05:00"), 14, "demo-bike.gpx"),
]


def add_seconds(hms, secs):
    h, m, s = (int(x) for x in hms.split(":"))
    total = h * 3600 + m * 60 + s + int(secs)
    return f"{total // 3600 % 24:02d}:{total // 60 % 60:02d}:{total % 60:02d}"


def day_iso(which):
    y, m, d = (int(x) for x in BASE_DAY.split("-"))
    if which == "prev":
        d -= 1  # BASE_DAY is never the 1st in practice; keep simple
    return f"{y:04d}-{m:02d}-{d:02d}"


def build(name, gpxtype, router, waypoints, speed, start, step, out):
    coords = resample(fetch_route(router, waypoints), step)
    day, hms = day_iso(start[0]), start[1]
    # GPX carries no speed of its own, so the reader derives it from these timestamps — which makes
    # the pace here the pace its chart draws. Seeded, so a re-run over the same route repeats.
    profile = wander(len(coords), random.Random(11))
    pts, last, elapsed = [], None, 0.0
    for i, c in enumerate(coords):
        if last is not None:
            elapsed += meters(last, c) / (speed * profile[i])
        t = f"{day}T{add_seconds(hms, elapsed)}Z"
        pts.append(f'<trkpt lat="{c[1]:.6f}" lon="{c[0]:.6f}"><time>{t}</time></trkpt>')
        last = c
    (OUT / out).write_text(
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<gpx version="1.1" creator="breadcrumb-demo" '
        'xmlns="http://www.topografix.com/GPX/1/1">\n'
        f'<trk><name>{name}</name><type>{gpxtype}</type>'
        f'<trkseg>{"".join(pts)}</trkseg></trk>\n</gpx>\n')
    print(f"wrote tools/demo-data/{out}  ({len(pts)} pts)")


def main():
    if "--offline" in sys.argv:
        # Nothing here is committed, so a fresh clone has none of it — and reporting a keep that
        # kept nothing would send someone off to import files that do not exist.
        kept = sorted(p.name for p in OUT.glob("*.gpx")) if OUT.exists() else []
        if not kept:
            raise SystemExit("offline: no .gpx in tools/demo-data/ to keep — run without --offline")
        print(f"offline: keeping {', '.join(kept)}")
        return
    OUT.mkdir(parents=True, exist_ok=True)
    for name, gpxtype, router, wp, speed, start, step, out in TRACKS:
        build(name, gpxtype, router, wp, speed, start, step, out)


if __name__ == "__main__":
    main()
