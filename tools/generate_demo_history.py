#!/usr/bin/env python3
"""Generate a synthetic demo *history* — the backup file screenshots are shot from.

Writes tools/demo-data/demo-backup.json.gz in BackupExporter's format, restorable from the
Timeline's empty state in one step. A backup rather than GPX because the screens the README
describes need what GPX cannot carry:

  - named places with categories, and the HOME tag journeys are defined against;
  - per-point accuracy / satellite count / C/N0, without which the map's colour modes drop to
    speed alone (`availableColorModes` refuses metrics an imported track can't have);
  - the liveness log, so silence between tracks reads as an observed stay rather than an
    inferred one.

Two spans, not one. The routine — commutes, gym, weekends — runs thirteen months, because Statistics
reads one month against the year behind it and an empty year is the emptiest screen in the app. The
history reaches further only where a journey needs it: Journeys groups by calendar year, and one
year of history gives it a single heading and no sense that a history accumulates, so the oldest
journeys sit alone in their weeks with no routine around them. Only the most recent month is
generated at full fidelity — that is what the timeline, the map and a track's detail are shot from;
everything earlier keeps every Nth fix, since months that far back are read as totals and place
counts rather than as geometry. Together those keep the file near a couple of megabytes, which
matters for something pushed to a phone before every shoot.

The history is a routine plus the journeys in JOURNEYS: weekday commutes, a gym and a supermarket
run, weekend walks and rides, and nights abroad reached by flights nobody recorded — which is what
leaves gap rows on the timeline for the add-trip form to fill, and what gives Insights journeys
with nights, cities and countries to count.

NEVER use real personal history for screenshots; use this.

Usage:
  python3 tools/generate_demo_history.py                  # history ending today
  python3 tools/generate_demo_history.py --base-day 2026-08-06
  python3 tools/generate_demo_history.py --verify         # re-read what was written, check it

Routes come from public OSRM routers, each distinct route fetched once and replayed across the
days that use it with jittered departure times and fresh per-fix noise — so the file has hundreds
of tracks for a dozen router calls, and no two runs produce the same one. The output is
gitignored: this script is what the repository keeps, and a shoot wants a fresh run regardless,
since the history has to end today.
"""

import argparse
import datetime as dt
import gzip
import json
import math
import random

from demo_routes import (
    BIKE, CAR, FOOT, GYM, HOME, MARKET, OUT_DIR, PARK, REPO, RIVER, SCHOOL, WORK,
    fetch_route, meters, resample, wander,
)

OUT = OUT_DIR / "demo-backup.json.gz"

# Copies of BackupExporter's FORMAT / VERSION / POINT_FIELDS. The first two are verified on import
# and a mismatch is refused outright; POINT_FIELDS is not — the importer reads each point by the
# header written here, so a field the exporter gains and this list lacks restores as null rather
# than failing. The PlaceCategory codes and ActivityType names below are copied the same way, and
# nothing validates those at all.
FORMAT = "breadcrumb-export"
VERSION = 1
POINT_FIELDS = [
    "timestamp", "lat", "lon", "alt", "accuracy", "speed", "bearing",
    "verticalAccuracy", "speedAccuracy", "bearingAccuracy", "satellitesInFix", "cn0",
    "ignored", "ignoreReason", "segmentStart",
]
# The columns anything outside build_track() reads back, resolved through the list above rather
# than written as 0/1/2 wherever they are needed.
TS, LAT, LON = (POINT_FIELDS.index(name) for name in ("timestamp", "lat", "lon"))

ROUTINE_DAYS = 395   # days of weekday-and-weekend routine, so Statistics has a full year behind
                     # its month. The history itself reaches further back, but only for journeys.
RECENT_DAYS = 28     # the tail generated at full fidelity
COARSE_STRIDE = 6    # older days keep every Nth fix — totals survive, geometry needn't
MIN_DWELL_MS = 6 * 60 * 1000   # shortest stay between two tracks

# The home city is demo_routes'. Only the destinations are this generator's own — the GPX fixture
# never leaves town, so nothing else needs them.
#
# Destinations, mostly abroad so the yearly totals have countries besides home's to count. Porto is
# the deliberate exception: a journey is a run of nights away from home, not a border crossing, and
# a set where every trip is international would show that rule only in the case it does not test.
#
# Each is a hotel plus two places to walk to, which is all three legs need. Central, walkable
# coordinates — the foot router snaps them to the nearest way, so they need only be about right.
SEV_HOTEL = (-5.9930, 37.3860)
SEV_CATHEDRAL = (-5.9931, 37.3892)
SEV_PLAZA = (-5.9869, 37.3773)
SEV_TRIANA = (-6.0030, 37.3860)
BDX_HOTEL = (-0.5770, 44.8410)
BDX_QUAY = (-0.5690, 44.8430)
BDX_PARK = (-0.5850, 44.8460)
POR_HOTEL = (-8.6110, 41.1470)
POR_RIBEIRA = (-8.6130, 41.1405)
POR_CLERIGOS = (-8.6145, 41.1455)
LON_HOTEL = (-0.1250, 51.5100)
LON_THAMES = (-0.1195, 51.5065)
LON_PARK = (-0.1420, 51.5075)
MAD_HOTEL = (-3.7030, 40.4180)
MAD_PRADO = (-3.6920, 40.4140)
MAD_RETIRO = (-3.6830, 40.4150)
AMS_HOTEL = (4.8900, 52.3720)
AMS_CANAL = (4.8840, 52.3680)
AMS_MUSEUM = (4.8850, 52.3600)

# label, (route key, which end of it), category code (PlaceCategory.code), capture radius.
#
# Pinned at the route's *routed* endpoint rather than the coordinate asked for above: the router
# snaps every waypoint to the nearest way, and a pin left at the request can end up outside its own
# capture radius — the place then stops claiming its cluster and the timeline labels the stay with
# a city name instead. Every place is also the *end* of some track, because a stay forms between
# tracks: a place passed through mid-route accumulates no visits at all.
PLACES = [
    ("Home", ("commute-out", 0), "home", 120.0),
    ("Work", ("commute-out", -1), "work", 150.0),
    ("Gym", ("gym", -1), "sports", 90.0),
    ("Supermarket", ("market-out", -1), "groceries", 90.0),
    ("School", ("school-drop", -1), "kids_school", 90.0),
]

# key -> (activityType, router, waypoints, m/s, resample step m). Fetched once, replayed often.
# Two tables key into this one by hand: PLACES names a route and an end to pin itself at, and each
# journey's prefix expands to its `-out` / `-back` / `-loop` legs. Renaming a key here means
# following it into both — a miss raises KeyError at generation rather than corrupting the file.
ROUTES = {
    "commute-out": ("DRIVING", CAR, [HOME, WORK], 9.0, 25),
    "commute-back": ("DRIVING", CAR, [WORK, HOME], 8.2, 25),
    "school-drop": ("DRIVING", CAR, [HOME, SCHOOL], 7.0, 25),
    "school-to-work": ("DRIVING", CAR, [SCHOOL, WORK], 7.5, 25),
    "gym": ("WALKING", FOOT, [HOME, GYM], 1.35, 12),
    "gym-back": ("WALKING", FOOT, [GYM, HOME], 1.3, 12),
    "market-out": ("WALKING", FOOT, [HOME, MARKET], 1.3, 12),
    "market-back": ("WALKING", FOOT, [MARKET, HOME], 1.3, 12),
    "park-walk": ("WALKING", FOOT, [HOME, PARK, HOME], 1.4, 12),
    "river-ride": ("CYCLING", BIKE, [HOME, RIVER], 4.6, 18),
    "river-ride-back": ("CYCLING", BIKE, [RIVER, HOME], 4.4, 18),
    "sev-out": ("WALKING", FOOT, [SEV_HOTEL, SEV_CATHEDRAL, SEV_PLAZA], 1.25, 12),
    "sev-back": ("WALKING", FOOT, [SEV_PLAZA, SEV_HOTEL], 1.3, 12),
    "sev-loop": ("WALKING", FOOT, [SEV_HOTEL, SEV_TRIANA, SEV_HOTEL], 1.3, 12),
    "bdx-out": ("WALKING", FOOT, [BDX_HOTEL, BDX_QUAY], 1.25, 12),
    "bdx-back": ("WALKING", FOOT, [BDX_QUAY, BDX_HOTEL], 1.3, 12),
    "bdx-loop": ("WALKING", FOOT, [BDX_HOTEL, BDX_PARK, BDX_HOTEL], 1.3, 12),
    "por-out": ("WALKING", FOOT, [POR_HOTEL, POR_RIBEIRA], 1.25, 12),
    "por-back": ("WALKING", FOOT, [POR_RIBEIRA, POR_HOTEL], 1.3, 12),
    "por-loop": ("WALKING", FOOT, [POR_HOTEL, POR_CLERIGOS, POR_HOTEL], 1.3, 12),
    "lon-out": ("WALKING", FOOT, [LON_HOTEL, LON_THAMES], 1.25, 12),
    "lon-back": ("WALKING", FOOT, [LON_THAMES, LON_HOTEL], 1.3, 12),
    "lon-loop": ("WALKING", FOOT, [LON_HOTEL, LON_PARK, LON_HOTEL], 1.3, 12),
    "mad-out": ("WALKING", FOOT, [MAD_HOTEL, MAD_PRADO], 1.25, 12),
    "mad-back": ("WALKING", FOOT, [MAD_PRADO, MAD_HOTEL], 1.3, 12),
    "mad-loop": ("WALKING", FOOT, [MAD_HOTEL, MAD_RETIRO, MAD_HOTEL], 1.3, 12),
    "ams-out": ("WALKING", FOOT, [AMS_HOTEL, AMS_CANAL], 1.25, 12),
    "ams-back": ("WALKING", FOOT, [AMS_CANAL, AMS_HOTEL], 1.3, 12),
    "ams-loop": ("WALKING", FOOT, [AMS_HOTEL, AMS_MUSEUM, AMS_HOTEL], 1.3, 12),
}

# (days before the end of the history that the first night away falls, nights, route prefix).
# The flights themselves are never recorded — those absences are the gap rows.
#
# Offsets are counted back from the last day, which is the day of generation, so which calendar
# year a journey lands in follows from when the file is made. These are spaced to give Journeys
# three year headings — two journeys in the newest year, three in the one before, one in the
# oldest — with a few weeks' clearance either side of each new year, so a shoot some weeks late
# does not slide a journey into the wrong heading. generate prints the dates it produced; if the
# spread has drifted, move the offsets rather than reading the count off this list.
JOURNEYS = [
    (18, 4, "sev"),
    (205, 5, "bdx"),
    (270, 2, "por"),
    (380, 4, "lon"),
    (500, 3, "mad"),
    (650, 5, "ams"),
]

# The history reaches back to whichever comes first: the routine, or the oldest journey plus a day
# of lead, so the first midnight falls before that journey's first day rather than on it.
#
# A journey older than the routine stands alone: no commutes around it, just its own week. It still
# derives, because each night away is bracketed within the block — the day of arrival ends at the
# hotel, the day of departure back at home — and because the liveness log is ARMED throughout, so
# the months of silence either side are an attested stay at home rather than a gap, and their nights
# are placed at home where they neither open a run nor close one. Generating a second year of
# commutes to reach a journey inside it would double the file for days no screen is shot from.
JOURNEY_LEAD_DAYS = 2
HISTORY_DAYS = max(ROUTINE_DAYS,
                   max(from_end for from_end, _, _ in JOURNEYS) + JOURNEY_LEAD_DAYS)


def day_index(from_end):
    """Turn a distance back from the last day into an index counted from the history's first.

    Everything here is stated from the end — a journey's offset, the routine's length — because the
    history ends on the day it is generated. The schedule, the routine's boundary and the check all
    convert through this one function rather than each doing the arithmetic: the tail is cut at
    generation time, so anything counting back from the last day would slide.
    """
    return HISTORY_DAYS - from_end


def journey_hotel(prefix):
    """Where a journey sleeps: the first waypoint of its leg out, which is where it returns to."""
    return ROUTES[f"{prefix}-out"][2][0]


def clock(h, m):
    return h * 3600 + m * 60


def bearing_deg(a, b):
    lat1, lat2 = math.radians(a[1]), math.radians(b[1])
    dlon = math.radians(b[0] - a[0])
    y = math.sin(dlon) * math.cos(lat2)
    x = math.cos(lat1) * math.sin(lat2) - math.sin(lat1) * math.cos(lat2) * math.cos(dlon)
    return (math.degrees(math.atan2(y, x)) + 360) % 360


def coarsen(coords, stride):
    """Every Nth fix, both ends kept — a month read as totals doesn't need the corners."""
    out = coords[::stride]
    if out[-1] != coords[-1]:
        out.append(coords[-1])
    return out


def build_track(activity, coords, start_ms, speed, rng):
    """One track: points with the fix metadata a recorded track carries, plus its own aggregates."""
    base_alt = rng.uniform(20, 70)
    profile = wander(len(coords), rng)
    relief = wander(len(coords), rng, floor=0.0, ceiling=2.0, window=12)
    points, distance = [], 0.0
    t_ms, last = float(start_ms), None
    for i, c in enumerate(coords):
        v = speed * profile[i]
        if last is not None:
            seg = meters(last, c)
            distance += seg
            t_ms += seg / max(v, 0.3) * 1000.0
        acc = round(rng.uniform(3.5, 11.0), 1)
        # Named, then laid out in POINT_FIELDS order — so the header this file writes is what
        # decides each column, rather than a bare list of fifteen values agreeing with it by eye.
        fix = {
            "timestamp": int(t_ms),
            # Five decimals is ~1.1 m — coarser than the accuracy radius written on the same
            # point, so nothing downstream can tell, and a digit off every one of ~70k pairs is
            # ~5% of the file. A place's pin keeps six: there is a handful of those, so the size
            # is nothing, and it is the one coordinate that has to land where it was put.
            "lat": round(c[1] + rng.uniform(-1.5e-5, 1.5e-5), 5),
            "lon": round(c[0] + rng.uniform(-1.5e-5, 1.5e-5), 5),
            "alt": round(base_alt + 30 * (relief[i] - 1.0) + rng.uniform(-0.8, 0.8), 1),
            "accuracy": acc,
            "speed": round(v, 2),
            # The first fix has no previous point to bear from, so it borrows the next one's.
            "bearing": round(bearing_deg(last, c) if last is not None
                             else bearing_deg(c, coords[1]), 1),
            "verticalAccuracy": round(acc * rng.uniform(1.4, 2.2), 1),
            "speedAccuracy": round(rng.uniform(0.25, 0.9), 2),
            "bearingAccuracy": round(rng.uniform(4.0, 18.0), 1),
            "satellitesInFix": rng.randint(9, 21),
            "cn0": round(rng.uniform(24.0, 39.0), 1),
            "ignored": 0,
            "ignoreReason": None,
            "segmentStart": 1 if i == 0 else 0,
        }
        points.append([fix[name] for name in POINT_FIELDS])
        last = c
    return {
        "id": 0,  # renumbered once every track is in time order
        "activityType": activity,
        "startedAt": points[0][TS],
        "endedAt": points[-1][TS],
        "source": "recorded",
        "distanceMeters": round(distance, 1),
        "pointCount": len(points),
        "ignoredCount": 0,
        "startLat": points[0][LAT],
        "startLon": points[0][LON],
        "endLat": points[-1][LAT],
        "endLon": points[-1][LON],
        "points": points,
    }


def journey_days():
    """day offset -> (leg, prefix) for every day spent away, across all journeys."""
    days = {}
    for from_end, nights, prefix in JOURNEYS:
        first = day_index(from_end)
        for n in range(nights + 1):
            leg = "arrive" if n == 0 else ("leave" if n == nights else "middle")
            days[first + n] = (leg, prefix)
    return days


def schedule(base_day):
    """(day offset, route key, seconds past midnight) for the whole history, in order."""
    away = journey_days()
    plan = []
    for day in range(HISTORY_DAYS):
        date = base_day - dt.timedelta(days=HISTORY_DAYS - 1 - day)
        if day in away:
            leg, prefix = away[day]
            # Every day away must *end* at the hotel: a night is placed by the interval around it,
            # and an interval whose ends disagree is a gap, which can neither open nor close a
            # journey. The flights are the two absences either side of each block.
            if leg == "arrive":
                # Still at home that morning, so the night before the flight is a stay rather
                # than something inside the gap — an unplaceable night can't open the run.
                plan.append((day, "park-walk", clock(9, 10)))
                plan.append((day, f"{prefix}-out", clock(16, 40)))
                plan.append((day, f"{prefix}-back", clock(19, 25)))
            elif leg == "leave":
                plan.append((day, f"{prefix}-loop", clock(9, 30)))
                # Home again the same evening, so the night that closes the run is at home
                # rather than inside the return flight's gap.
                plan.append((day, "market-out", clock(19, 40)))
                plan.append((day, "market-back", clock(20, 15)))
            else:
                plan.append((day, f"{prefix}-out", clock(10, 15)))
                plan.append((day, f"{prefix}-back", clock(17, 50)))
            continue
        # Older than the routine: nothing but the journeys above. Their weeks are self-contained,
        # and the silence around them reads as a stay at home.
        if day < day_index(ROUTINE_DAYS):
            continue
        if date.weekday() >= 5:
            plan.append((day, "park-walk", clock(10, 20)))
            if date.weekday() == 6:
                plan.append((day, "river-ride", clock(15, 5)))
                plan.append((day, "river-ride-back", clock(17, 20)))
            continue
        if date.weekday() in (1, 3):
            plan.append((day, "school-drop", clock(8, 5)))
            plan.append((day, "school-to-work", clock(8, 30)))
        else:
            plan.append((day, "commute-out", clock(8, 15)))
        plan.append((day, "commute-back", clock(18, 35)))
        if date.weekday() in (0, 2):
            plan.append((day, "gym", clock(19, 40)))
            plan.append((day, "gym-back", clock(21, 5)))
        if date.weekday() == 4:
            plan.append((day, "market-out", clock(19, 30)))
            plan.append((day, "market-back", clock(20, 5)))
    return plan


def generate(base_day, now_ms, seed=7):
    rng = random.Random(seed)
    fine, coarse = {}, {}
    for key, (_, router, waypoints, _, step) in ROUTES.items():
        fine[key] = resample(fetch_route(router, waypoints), step)
        coarse[key] = coarsen(fine[key], COARSE_STRIDE)
        print(f"  route {key}: {len(fine[key])} pts ({len(coarse[key])} coarse)")

    midnight = dt.datetime.combine(base_day - dt.timedelta(days=HISTORY_DAYS - 1),
                                   dt.time(0, 0), dt.timezone.utc)
    history_start_ms = int(midnight.timestamp() * 1000)
    detail_from = HISTORY_DAYS - RECENT_DAYS

    planned = sorted(
        (history_start_ms + day * 86_400_000 + (at + rng.randint(-420, 420)) * 1000, day, key)
        for day, key, at in schedule(base_day)
    )
    # A scheduled departure is a wish, not a guarantee: how long a leg takes falls out of the speed
    # walk, so two legs of one errand can collide. Each track therefore starts no earlier than the
    # last one ended plus a dwell — which is also the stay that makes the errand read as a stop.
    tracks, last_end = [], 0
    for start_ms, day, key in planned:
        activity, _, _, speed, _ = ROUTES[key]
        geometry = fine[key] if day >= detail_from else coarse[key]
        tracks.append(build_track(activity, geometry, max(start_ms, last_end + MIN_DWELL_MS),
                                  speed, rng))
        last_end = tracks[-1]["endedAt"]
    # The last day is filled on the same schedule as any other, so the hours after the moment of
    # generation would arrive as history that hasn't happened — a drive this evening, shown at
    # three in the afternoon. Cutting them leaves today reading as a day in progress, which is
    # what a real install looks like and the better picture anyway.
    tracks = [t for t in tracks if t["endedAt"] <= now_ms]
    for i, t in enumerate(tracks):
        t["id"] = i + 1

    places = []
    for i, (label, (route, end), category, radius) in enumerate(PLACES):
        lon, lat = fine[route][end]
        places.append({"id": i + 1, "label": label, "lat": round(lat, 6), "lon": round(lon, 6),
                       "createdAt": history_start_ms, "radiusM": radius, "category": category})
    # Armed for the whole history and never disarmed: every silence between tracks is then an
    # attested stay, which is what the timeline should look like in a screenshot.
    liveness = [{"type": "ARMED", "at": history_start_ms, "until": None}]

    return {
        "format": FORMAT,
        "version": VERSION,
        "exportedAt": tracks[-1]["endedAt"],
        "trackCount": len(tracks),
        "pointFields": POINT_FIELDS,
        "tracks": tracks,
        "places": places,
        "liveness": liveness,
    }


def verify(doc):
    """Invariants a restore depends on; a demo file that fails these wastes a whole shoot."""
    assert doc["format"] == FORMAT, "wrong format marker"
    assert doc["version"] <= VERSION, "version newer than the app understands"
    assert doc["pointFields"] == POINT_FIELDS, "point field order does not match the exporter"
    assert doc["trackCount"] == len(doc["tracks"]), "trackCount disagrees with the tracks"
    previous_end = 0
    for t in doc["tracks"]:
        pts = t["points"]
        assert len(pts) == t["pointCount"], f"track {t['id']} pointCount"
        assert t["startedAt"] == pts[0][TS] and t["endedAt"] == pts[-1][TS], \
            f"track {t['id']} bounds disagree with its points"
        assert t["startedAt"] > previous_end, f"track {t['id']} overlaps the one before it"
        assert all(pts[i][TS] < pts[i + 1][TS] for i in range(len(pts) - 1)), \
            f"track {t['id']} has non-ascending timestamps"
        assert all(len(p) == len(POINT_FIELDS) for p in pts), f"track {t['id']} point arity"
        previous_end = t["endedAt"]
    home = next((p for p in doc["places"] if p["category"] == "home"), None)
    assert home, "no HOME place — Insights would fall back to the biggest cluster"
    home_at = (home["lon"], home["lat"])

    # Every named place must sit within its own capture radius of a track end, or it claims no
    # cluster: the timeline then labels the stay with a city name and the place shows no visits.
    ends = [(t["endLon"], t["endLat"]) for t in doc["tracks"]]
    for p in doc["places"]:
        nearest = min(meters((p["lon"], p["lat"]), e) for e in ends)
        assert nearest <= p["radiusM"], (
            f"place {p['label']} is {nearest:.0f} m from the nearest track end, "
            f"outside its {p['radiusM']:.0f} m radius"
        )
    # Can't false-positive on a file generated earlier: those tracks are all in the past by now.
    now_ms = int(dt.datetime.now(dt.timezone.utc).timestamp() * 1000)
    assert doc["tracks"][-1]["endedAt"] <= now_ms, "the history ends in the future"

    # A journey only derives if each night away is bracketed by tracks that agree on where the
    # night was spent. A day away ending anywhere but the hotel makes that night a gap instead,
    # and a gap can neither open nor close a run — the whole journey silently vanishes.
    by_day = {}
    for t in doc["tracks"]:
        by_day.setdefault(t["startedAt"] // 86_400_000, []).append(t)
    days = sorted(by_day)
    # Keyed on the day a track actually falls in, not on its position among the days that have
    # one: outside the routine window most days hold nothing, so counting entries would walk off
    # into the wrong week. The ARMED record is stamped at the history's first midnight, which is
    # the one reference that survives both the sparse days and the cut tail.
    assert doc["liveness"][0]["type"] == "ARMED", "first liveness entry is not the ARMED stamp"
    first_epoch_day = doc["liveness"][0]["at"] // 86_400_000
    for from_end, nights, prefix in JOURNEYS:
        first = day_index(from_end)
        for n in range(nights + 1):
            last = max(by_day[first_epoch_day + first + n], key=lambda t: t["endedAt"])
            anchor = home_at if n == nights else journey_hotel(prefix)
            # 250 m is the app's own reach: PlaceClusterer.DEFAULT_RADIUS_M plus
            # StayDeriver.Params.agreementRadiusM. Inside it the two ends of a night still agree.
            off = meters((last["endLon"], last["endLat"]), anchor)
            assert off < 250, f"{prefix} day {n} ends {off:.0f} m from where it should"

    months = {dt.datetime.fromtimestamp(t["startedAt"] / 1000, dt.timezone.utc).strftime("%Y-%m")
              for t in doc["tracks"]}
    print(f"ok: {len(doc['tracks'])} tracks, "
          f"{sum(t['pointCount'] for t in doc['tracks'])} points, "
          f"{len(doc['places'])} places, {len(days)} days over {len(months)} months")

    # Which year each journey landed in is a consequence of the day this ran, not of JOURNEYS, so
    # it is printed rather than left to be discovered on the Insights screen after a shoot.
    per_year = {}
    for from_end, nights, prefix in JOURNEYS:
        first_day = min(by_day[first_epoch_day + day_index(from_end)],
                        key=lambda t: t["startedAt"])
        date = dt.datetime.fromtimestamp(first_day["startedAt"] / 1000, dt.timezone.utc).date()
        per_year.setdefault(date.year, []).append(f"{prefix} {date:%d %b}, {nights}n")
    for year in sorted(per_year, reverse=True):
        found = per_year[year]
        print(f"  {year}: {len(found)} journey{'' if len(found) == 1 else 's'} — {'; '.join(found)}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base-day", default=None, help="last day of the history (YYYY-MM-DD)")
    ap.add_argument("--verify", action="store_true", help="check the existing file, write nothing")
    args = ap.parse_args()

    if args.verify:
        with gzip.open(OUT, "rt", encoding="utf-8") as f:
            verify(json.load(f))
        return

    now = dt.datetime.now(dt.timezone.utc)
    base_day = dt.date.fromisoformat(args.base_day) if args.base_day else now.date()
    print(f"fetching routes (history ends {base_day})")
    doc = generate(base_day, int(now.timestamp() * 1000))
    verify(doc)
    OUT.parent.mkdir(parents=True, exist_ok=True)
    with gzip.open(OUT, "wt", encoding="utf-8", compresslevel=9) as f:
        json.dump(doc, f, separators=(",", ":"))
    print(f"wrote {OUT.relative_to(REPO)}  ({OUT.stat().st_size / 1024:.0f} KB)")


if __name__ == "__main__":
    main()
