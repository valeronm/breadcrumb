#!/usr/bin/env python3
"""Where the demo data goes, and how a route between two of those points is drawn.

Shared by both generators — generate_demo_tracks.py (GPX files, the fixture for exercising import)
and generate_demo_history.py (a whole backup, the file screenshots are shot from). It
exists so the two cannot disagree about the city: a coordinate defined twice is a coordinate that
gets moved once, and trips from another country then land in the middle of the demo timeline.

Neither generator may import from the other. This module is the only thing both are allowed to
depend on, which is what keeps that from becoming a cycle.
"""

import json
import math
import urllib.request
from pathlib import Path

# Where both generators write. Gitignored: the output is derived, large, and stale by design.
REPO = Path(__file__).resolve().parent.parent
OUT_DIR = REPO / "tools/demo-data"

# Public OSRM instances. Rate-limited and occasionally down: a generator fetches each distinct
# route once and replays it, rather than asking per day.
FOOT = "https://routing.openstreetmap.de/routed-foot/route/v1/foot"
BIKE = "https://routing.openstreetmap.de/routed-bike/route/v1/bike"
CAR = "https://router.project-osrm.org/route/v1/driving"

# Coimbra, as (lon, lat) — the order the routers speak. Each is snapped to the nearest routable
# way when a route is fetched, so they need only be in the right neighbourhood; anything that has
# to *be* somewhere exactly (a place's pin) is taken from the routed path instead of from here.
HOME = (-8.4100, 40.2020)
WORK = (-8.4750, 40.1830)
GYM = (-8.4060, 40.2060)
MARKET = (-8.4130, 40.2085)
SCHOOL = (-8.4155, 40.2005)
PARK = (-8.4310, 40.2065)
RIVER = (-8.4620, 40.2100)


def wander(n, rng, floor=0.35, ceiling=1.35, window=6):
    """A bounded random walk of length [n], lightly smoothed — the shape of speed and of terrain.

    Not a sine. A periodic function is invisible in the numbers and unmistakable the moment a
    track's chart is drawn — identical humps, end to end — and it holds the whole track inside one
    narrow band, which leaves the map's colouring a single flat hue. A walk wanders instead: it
    slows for a junction, runs for a while, climbs a hill and comes back down.
    """
    walk, x = [], 1.0
    for _ in range(n):
        x = max(floor, min(ceiling, x + rng.gauss(0, 0.12)))
        walk.append(x)
    smoothed = []
    for i in range(n):
        around = walk[max(0, i - window):min(n, i + window + 1)]
        smoothed.append(sum(around) / len(around))
    return smoothed


def meters(a, b):
    """Planar approximation between two (lon, lat) pairs — good to a metre at city scale."""
    lat = math.radians((a[1] + b[1]) / 2)
    return math.hypot((a[0] - b[0]) * 111320 * math.cos(lat), (a[1] - b[1]) * 111320)


def fetch_route(router, waypoints):
    """The road geometry through [waypoints], as the router returns it: a list of (lon, lat)."""
    coords = ";".join(f"{lon},{lat}" for lon, lat in waypoints)
    url = f"{router}/{coords}?geometries=geojson&overview=full"
    with urllib.request.urlopen(url, timeout=30) as r:
        data = json.load(r)
    if data.get("code") != "Ok":
        raise SystemExit(f"router error for {url}: {data.get('code')}")
    return data["routes"][0]["geometry"]["coordinates"]


def resample(coords, step):
    """Re-space a route to a fix every [step] metres, so timing can be even and speeds smooth."""
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
