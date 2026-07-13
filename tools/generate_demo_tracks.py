#!/usr/bin/env python3
"""Generate synthetic demo GPX tracks for store screenshots / manual testing.

Produces three chained Lisbon tracks in tools/demo-data/:
  demo-drive.gpx   Work -> Home            (yesterday evening)
  demo-walk.gpx    Home -> loop -> Home     (this morning)
  demo-bike.gpx    Home -> Belem            (midday)

The tracks deliberately chain through a common "Home" point so Breadcrumb's
stay-deriver reads the gaps between them as one place ("Stayed at Home")
instead of "Moved without recording" — which is what makes the timeline
screenshot look like a real diary. Routes are resampled to uniform spacing
with smooth timing so the speed chart has no fake spikes.

NEVER use real personal tracks for store screenshots; use these.

Usage:
  python3 tools/generate_demo_tracks.py            # regenerate from routers
  # then import the .gpx into a clean *release* install and screenshot.

Routes are fetched from public OSRM routers at run time (non-deterministic, so
the committed .gpx files are the source of truth); pass --offline to skip the
network and keep the existing files. Times are relative to BASE_DAY below —
bump it so "today/yesterday" stay correct when you re-shoot.
"""

import json
import math
import sys
import urllib.request
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
OUT = REPO / "tools/demo-data"

# Bump to the shoot date; walk/bike land on this day, drive on the day before.
BASE_DAY = "2026-07-13"

HOME = (-9.1625, 38.7185)   # Campo de Ourique
WORK = (-9.2076, 38.6979)   # west (drive origin)
BELEM = (-9.1960, 38.6975)  # riverside (bike destination)

# (name, gpx type, router, waypoints, m/s, start HH:MM:SS on which day, resample step m)
TRACKS = [
    ("Drive home", "driving", "https://router.project-osrm.org/route/v1/driving",
     [WORK, HOME], 9.5, ("prev", "18:40:00"), 18, "demo-drive.gpx"),
    ("Morning walk", "walking", "https://routing.openstreetmap.de/routed-foot/route/v1/foot",
     [HOME, (-9.1560, 38.7205), (-9.1600, 38.7240), HOME], 1.35, ("day", "08:12:00"), 10, "demo-walk.gpx"),
    ("Ride to Belem", "cycling", "https://routing.openstreetmap.de/routed-bike/route/v1/bike",
     [HOME, BELEM], 4.6, ("day", "11:05:00"), 14, "demo-bike.gpx"),
]


def meters(a, b):
    lat = math.radians((a[1] + b[1]) / 2)
    return math.hypot((a[0] - b[0]) * 111320 * math.cos(lat), (a[1] - b[1]) * 111320)


def fetch_route(router, waypoints):
    coords = ";".join(f"{lon},{lat}" for lon, lat in waypoints)
    url = f"{router}/{coords}?geometries=geojson&overview=full"
    with urllib.request.urlopen(url, timeout=30) as r:
        data = json.load(r)
    if data.get("code") != "Ok":
        raise SystemExit(f"router error for {url}: {data.get('code')}")
    return data["routes"][0]["geometry"]["coordinates"]


def resample(coords, step):
    out = [coords[0]]
    carry = 0.0
    for i in range(1, len(coords)):
        seg = meters(coords[i - 1], coords[i])
        if seg == 0:
            continue
        d = step - carry
        while d < seg:
            t = d / seg
            out.append([coords[i - 1][0] + (coords[i][0] - coords[i - 1][0]) * t,
                        coords[i - 1][1] + (coords[i][1] - coords[i - 1][1]) * t])
            d += step
        carry = seg - (d - step)
    out.append(coords[-1])
    return out


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
    pts, last, elapsed = [], None, 0.0
    for i, c in enumerate(coords):
        if last is not None:
            v = speed * (1 + 0.12 * math.sin(i / 6.0))  # gentle variation, no spikes
            elapsed += meters(last, c) / v
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
        print("offline: keeping existing tools/demo-data/*.gpx")
        return
    OUT.mkdir(parents=True, exist_ok=True)
    for name, gpxtype, router, wp, speed, start, step, out in TRACKS:
        build(name, gpxtype, router, wp, speed, start, step, out)


if __name__ == "__main__":
    main()
