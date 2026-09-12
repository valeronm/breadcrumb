#!/usr/bin/env python3
"""Summarise a recorder log exported from Settings → Logs into the figures recording decisions rest on.

    ./tools/log_report.py breadcrumb-logs.txt [--since MM-DD] [--until MM-DD] [--daily]
    ./tools/log_report.py breadcrumb-logs.txt --compare other-phone.txt

Lines are recognised by their English wording, across builds that word them differently. A line
this script does not know is counted at the end of the report, so a reworded line shows up there
rather than as a quietly wrong figure.

Log lines carry no year: dates are placed in --year, and a month that goes backwards starts the
next one.

GPS time is exact on builds that log "location updates stopped". On older builds an interval ends at
the next give-up, fence arming, probe start or disarm, which is where those builds turned GPS off.
"""

import argparse
import collections
import datetime
import re
import sys

LINE = re.compile(r"^(\d\d)-(\d\d) (\d\d):(\d\d):(\d\d)\.(\d+) ([VDIWE]) (.*)$")

PATTERNS = [(name, re.compile(rx)) for name, rx in [
    ("arm", r"handleStart: arming \(autoRecord=\w+\)(?: \[(?P<build>[^\]]*)\])?$"),
    ("disarm", r"handleStop: disarming$"),
    ("dead", r"watchdog: armed but service dead — restarting$"),
    ("deaf", r"reading (?P<late>\d+)s late \(advanced -?\d+ms\) — registration deaf"),
    ("transition", r"transition (?P<dir>ENTER|EXIT) (?P<act>\w+) \((?P<ago>[\d.]+)s ago\)$"),
    ("apply", r"applyActivity: (?P<prev>\w+) -> (?P<act>\w+) \(track=(?P<track>\w+)"
              r"(?: paused=(?P<paused>\w+))?(?: reading=-(?P<lag>\d+)s)?(?: ground=(?P<ground>\w+))?\)$"),
    ("open", r"\s*-> (?P<how>opened|continued) (?P<act>\w+) track (?P<track>\d+)$"),
    ("relabel", r"\s*-> relabelled track (?P<track>\d+) as (?P<act>\w+)$"),
    ("closing", r"\s*-> closing track (?P<track>\d+)$"),
    ("verdict", r"track (?P<track>\d+) \((?P<act>\w+)\): (?P<pts>\d+) pts, (?P<m>\d+) m, (?P<s>-?\d+)s vs .* -> "
                r"(?P<verdict>\w+)$"),
    ("carrier", r"track (?P<track>\d+): carrier evidence proven — finishing as (?P<act>\w+)$"),
    ("overrun", r"track \d+: (?:start|end|no) overrun"),
    ("pause_expired", r"pause expired — finalizing track (?P<track>\d+)$"),
    ("arrival", r"arrival watch: still for (?P<s>\d+)s — (?:closing|pausing)$"),
    ("fence_exit", r"departure fence EXIT$"),
    ("fence_arm_from", r"departure fence: arming from last known \((?P<provider>\w+)(?:, acc=(?P<acc>\w+))?, "
                       r"(?P<age>-?\d+)s old\)$"),
    ("fence_armed", r"departure fence armed \("),
    ("fence_open", r"departure: opening a Moving track \((?P<latency>[^)]*)\)$"),
    ("probe_open", r"departure: probe saw the phone leave \((?P<latency>[^,]*), (?P<gap>\d+)m of (?P<bar>\d+)m "
                   r"\(margin (?P<margin>\d+)\), acc=(?P<acc>\d+)m, (?P<age>-?\d+)s old\)$"),
    ("departure_ignored", r"departure ignored — already recording"),
    ("probe_start", r"departure probe started \("),
    ("probe_stop", r"departure probe stopped \((?P<n>\d+) position\(s\)\)$"),
    ("watch", r"departure watch: (?P<gap>\d+)m of (?P<bar>\d+)m \(margin (?P<margin>\d+)\) \(acc=(?P<acc>\d+)m"),
    ("motion_fired", r"motion trigger fired$"),
    ("gave_up", r"no-fix guard: probe gave up — GPS off"),
    ("probing_again", r"no-fix guard: probing again \((?P<signal>\w+)\)$"),
    ("gps_start", r"location updates started$"),
    ("gps_stop", r"location updates stopped$"),
    ("drop", r"fix dropped — no recent GNSS backing \(acc=(?P<acc>[\d.]+|null)\)$"),
    ("hold", r"motion cross-check: holding (?P<act>\w+) — (?P<kind>\w+)$"),
    ("release", r"motion cross-check: releasing the held (?P<act>\w+)$"),
]]

KNOWN = [re.compile(rx) for rx in [
    r"snapshot ", r"watchdog", r"transition ", r"removing transition updates", r"cancelling transition PendingIntent",
    r"boot receiver", r"edge-stay sweep", r"stats sweep", r"derivation rebuild", r"purged \d+ discarded",
    r"cleared \d+ track", r"handleStart:", r"departure fence", r"departure watch anchored", r"motion trigger",
    r"motion cross-check", r"backup export", r"gpx import", r"manual track", r"live delivery resumed",
    r"activity detection not responding", r"\s*EXIT \w+ -> treating as STILL", r"track \d+: \d+ jump fixes restored",
    r"track \d+ split at", r"stay after track", r"unreadable gap reason", r"online place search", r"onReceive:",
    r"geofence event error", r"setup state read", r"housekeeping failed", r"recording coroutine failed",
    r".* in \d+ ms$", r".* started$", r".* failed", r".* FAILED", r".* ignored — not armed$",
]]

TRIGGERS = ("fence exit", "departure probe")

# Where a build that logs no "location updates stopped" turned GPS off.
INFERRED_GPS_END = ("disarm", "fence_arm_from", "fence_armed", "probe_start", "gave_up")
CAUSES = ("Activity Recognition", "departure probe", "fence exit", "Activity Recognition after a pause",
          "Activity Recognition mid-track", "unknown", "before the log")


Event = collections.namedtuple("Event", "dt kind g")


class Track:
    def __init__(self, tid, act, cause, opened, extra=None):
        self.id, self.act, self.cause, self.opened, self.extra = tid, act, cause, opened, extra or {}
        self.pts = self.dist = self.dur = self.verdict = self.closed = None
        self.gps_s = 0.0
        self.drops = 0
        self.first_reading = None


def shape(msg):
    return re.sub(r"\d+(\.\d+)?", "N", msg)[:90]


def parse(path, year, since, until):
    events, unrecognised = [], collections.Counter()
    last_month = None
    with open(path, encoding="utf-8", errors="replace") as f:
        for raw in f:
            # An exported log can hold a run of NULs in front of an entry.
            line = raw.rstrip("\n").lstrip("\0")
            m = LINE.match(line)
            if not m:
                if line.strip():
                    unrecognised["(no timestamp) " + shape(line.strip())] += 1
                continue
            mo, d, hh, mi, ss, frac, _level, msg = m.groups()
            mo = int(mo)
            if last_month is not None and mo < last_month:
                year += 1
            last_month = mo
            day = f"{mo:02d}-{d}"
            if (since and day < since) or (until and day > until):
                continue
            dt = datetime.datetime(year, mo, int(d), int(hh), int(mi), int(ss), int(frac.ljust(6, "0")[:6]))
            for name, rx in PATTERNS:
                p = rx.match(msg)
                if p:
                    events.append(Event(dt, name, p.groupdict()))
                    break
            else:
                events.append(Event(dt, None, {}))
                if not any(k.match(msg) for k in KNOWN):
                    unrecognised[shape(msg)] += 1
    return events, unrecognised


def split_by_day(daily, start, end, key):
    t = start
    while t < end:
        nxt = min(end, datetime.datetime.combine(t.date() + datetime.timedelta(days=1), datetime.time()))
        daily[t.strftime("%m-%d")][key] += (nxt - t).total_seconds()
        t = nxt


class Report:
    def __init__(self, path, events, unrecognised):
        self.path, self.unrecognised = path, unrecognised
        self.first, self.last = (events[0].dt, events[-1].dt) if events else (None, None)
        self.lines = len(events)
        self.daily = collections.defaultdict(collections.Counter)
        self.tracks = {}
        self.builds = collections.Counter()
        self.disarms = self.deaf = self.arrivals = self.carrier = self.relabels = 0
        self.deaths, self.silences = [], []
        self.stitches = collections.Counter()
        self.fence_exits = self.departures_ignored = 0
        self.fence_sources, self.fence_accs = collections.Counter(), []
        self.probe_durations, self.watch_n = [], 0
        self.probes_with_positions = self.probes_still = 0
        self.fires, self.fire_outcomes = 0, collections.Counter()
        self.gave_up, self.retries = 0, collections.Counter()
        self.exact_gps = any(e.kind == "gps_stop" for e in events)
        self.gps_gave_up_s = 0.0
        self.gps_gave_up_n = 0
        self.drops, self.holds = [], []
        self._run(events)
        self.days = max(1, len(self.daily))

    def _run(self, events):
        armed, prev_dt, pending, cur = False, None, None, None
        gps_on = probe_start = hold_at = give_up_at = None
        probe_gaps = []

        def gps_close(at, gave_up=False):
            nonlocal gps_on
            if gps_on is None:
                return
            start, tid = gps_on
            gps_on = None
            secs = max(0.0, (at - start).total_seconds())
            split_by_day(self.daily, start, at, "gps_s")
            if tid in self.tracks:
                self.tracks[tid].gps_s += secs
            if gave_up:
                self.gps_gave_up_n += 1
                self.gps_gave_up_s += secs

        day, d = None, None
        for i, e in enumerate(events):
            g, k = e.g, e.kind
            # The log is in time order.
            if e.dt.date() != day:
                day, d = e.dt.date(), self.daily[e.dt.strftime("%m-%d")]
            if armed and prev_dt and k != "dead" and (e.dt - prev_dt).total_seconds() > 3600:
                self.silences.append((prev_dt, e.dt))
            if not self.exact_gps and k in INFERRED_GPS_END:
                gps_close(e.dt, gave_up=k == "gave_up")
            if k == "arm":
                armed = True
                self.builds[g["build"] or "(unstamped)"] += 1
            elif k == "disarm":
                armed = False
                self.disarms += 1
            elif k == "dead":
                self.deaths.append((prev_dt, e.dt))
                gps_close(prev_dt)
                probe_start = None
            elif k == "deaf":
                self.deaf += 1
            elif k == "apply":
                tid, pair = g["track"], None
                if tid == "null":
                    cause = "Activity Recognition"
                elif g["paused"] == "true":
                    cause = "Activity Recognition after a pause"
                else:
                    cause, pair = "Activity Recognition mid-track", f'{g["prev"]} → {g["act"]}'
                    t = self.tracks.get(tid)
                    if t and t.cause in TRIGGERS and t.first_reading is None:
                        t.first_reading = (g["act"], (e.dt - t.opened).total_seconds())
                pending = {"cause": cause, "pair": pair, "i": i}
            elif k == "fence_open":
                d["departures"] += 1
                pending = {"cause": "fence exit", "i": i}
            elif k == "probe_open":
                d["departures"] += 1
                pending = {"cause": "departure probe", "i": i, "probe": g}
            elif k == "departure_ignored":
                self.departures_ignored += 1
            elif k == "open":
                tid = g["track"]
                p = pending if pending and i - pending["i"] <= 4 else {"cause": "unknown"}
                pending = None
                if g["how"] == "continued" and tid in self.tracks:
                    self.stitches[p["cause"]] += 1
                else:
                    self.tracks[tid] = Track(tid, g["act"], p["cause"], e.dt, p)
                    d["opened"] += 1
                cur = tid
            elif k == "relabel":
                self.relabels += 1
                pending = None
            elif k == "verdict":
                tid = g["track"]
                t = self.tracks.get(tid) or self.tracks.setdefault(tid, Track(tid, g["act"], "before the log", None))
                t.pts, t.dist, t.dur, t.verdict, t.closed = int(g["pts"]), int(g["m"]), int(g["s"]), g["verdict"], e.dt
                d["closed"] += 1
                d[g["verdict"]] += 1
                if t.pts == 0:
                    d["zero"] += 1
                if cur == tid:
                    cur = None
            elif k == "carrier":
                self.carrier += 1
            elif k == "arrival":
                self.arrivals += 1
            elif k == "fence_exit":
                self.fence_exits += 1
            elif k == "fence_arm_from":
                self.fence_sources[g["provider"]] += 1
                if g["acc"] and g["acc"].rstrip("m").isdigit():
                    self.fence_accs.append(int(g["acc"].rstrip("m")))
            elif k == "probe_start":
                if probe_start is None:
                    probe_start, probe_gaps = e.dt, []
                    d["probes"] += 1
            elif k == "probe_stop":
                if probe_start is not None:
                    split_by_day(self.daily, probe_start, e.dt, "probe_s")
                    self.probe_durations.append((e.dt - probe_start).total_seconds())
                    if probe_gaps:
                        self.probes_with_positions += 1
                        self.probes_still += max(probe_gaps) < 20
                    probe_start = None
            elif k == "watch":
                self.watch_n += 1
                d["positions"] += 1
                if probe_start is not None:
                    probe_gaps.append(int(g["gap"]))
            elif k == "motion_fired":
                self.fires += 1
                d["fires"] += 1
                if probe_start is not None:
                    outcome = "extended a running probe"
                else:
                    ahead = [x.kind for x in events[i + 1:i + 4]]
                    outcome = ("started a departure probe" if "probe_start" in ahead
                               else "restarted GPS after a give-up" if "probing_again" in ahead
                               else "nothing")
                self.fire_outcomes[outcome] += 1
            elif k == "gave_up":
                self.gave_up += 1
                d["gave_up"] += 1
                give_up_at = e.dt
            elif k == "probing_again":
                self.retries[g["signal"]] += 1
            elif k == "gps_start":
                gps_close(e.dt)
                gps_on = (e.dt, cur)
            elif k == "gps_stop":
                gave = give_up_at is not None and (e.dt - give_up_at).total_seconds() < 2
                gps_close(e.dt, gave_up=gave)
                give_up_at = None
            elif k == "drop":
                self.drops.append(None if g["acc"] == "null" else float(g["acc"]))
                d["drops"] += 1
                if cur in self.tracks:
                    self.tracks[cur].drops += 1
            elif k == "hold":
                hold_at = e.dt
            elif k == "release" and hold_at:
                self.holds.append((e.dt - hold_at).total_seconds())
                hold_at = None
            prev_dt = e.dt
        if events:
            gps_close(events[-1].dt)

    def per_day(self, n):
        return n / self.days

    def closed_tracks(self):
        return [t for t in self.tracks.values() if t.verdict]

    def triggered(self):
        return [t for t in self.tracks.values() if t.cause in TRIGGERS]

    def gps_total(self):
        return sum(dd["gps_s"] for dd in self.daily.values())


def pct(ascending, q):
    return ascending[min(len(ascending) - 1, int(q * len(ascending)))] if ascending else 0


def hours(s):
    return f"{s / 3600:.1f} h"


def minutes(s):
    return f"{s / 60:.0f} min"


def table(header, rows):
    widths = [max(len(str(r[c])) for r in [header] + rows) for c in range(len(header))]
    fmt = "  " + "  ".join(f"{{:<{w}}}" if c == 0 else f"{{:>{w}}}" for c, w in enumerate(widths))
    print(fmt.format(*header))
    for r in rows:
        print(fmt.format(*r))


def section(title):
    print(f"\n== {title}")


def report(r, daily):
    print(f"{r.path}: {r.first:%m-%d %H:%M} → {r.last:%m-%d %H:%M}, {r.days} days with lines, {r.lines} lines")

    section("Coverage")
    for build, n in r.builds.items():
        print(f"  armed {n:>3}× on {build}")
    print(f"  disarms {r.disarms}, service found dead {len(r.deaths)}×")
    for before, at in r.deaths:
        print(f"    last line {before:%m-%d %H:%M}, found dead {at:%H:%M}")
    for before, at in r.silences:
        print(f"  silent while armed {before:%m-%d %H:%M} → {at:%m-%d %H:%M} ({hours((at - before).total_seconds())})")

    section("Trip starts by trigger")
    rows = []
    closed = r.closed_tracks()
    for cause in CAUSES:
        ts = [t for t in r.tracks.values() if t.cause == cause]
        if not ts:
            continue
        c = [t for t in ts if t.verdict]
        rows.append((cause, len(ts), f"{r.per_day(len(ts)):.1f}", sum(t.verdict == "keep" for t in c),
                     sum(t.verdict == "discard" for t in c), sum(t.verdict == "purge" for t in c),
                     sum(t.pts == 0 for t in c), hours(sum(t.gps_s for t in ts))))
    table(("opened by", "tracks", "/day", "kept", "discarded", "purged", "0 pts", "GPS"), rows)
    print("  (\"Activity Recognition mid-track\" is a split: its reading closed one moving track and opened the next)")
    if r.stitches:
        print("  stitched onto the previous track instead: " +
              ", ".join(f"{c} {n}" for c, n in r.stitches.most_common()))

    section("Fence and probe")
    trig = r.triggered()
    print(f"  departures: probe {sum(t.cause == 'departure probe' for t in trig)}, "
          f"fence {sum(t.cause == 'fence exit' for t in trig)} "
          f"({r.per_day(len(trig)):.1f}/day); fence EXIT {r.fence_exits}; "
          f"{r.departures_ignored} departures while already recording")
    rows = []
    for cause in TRIGGERS:
        ts = [t for t in trig if t.cause == cause]
        if not ts:
            continue
        moving = sorted(t.first_reading[1] for t in ts if t.first_reading and t.first_reading[0] != "STILL")
        rows.append((cause, len(ts), len(moving), f"{pct(moving, .5):.0f}s" if moving else "-",
                     sum(1 for t in ts if t.first_reading and t.first_reading[0] == "STILL"),
                     sum(1 for t in ts if not t.first_reading),
                     sum(t.verdict == "keep" for t in ts), sum(t.pts == 0 for t in ts if t.verdict)))
    table(("", "opened", "moving reading after", "median lead", "STILL after", "no reading", "kept", "0 pts"), rows)
    probes = [t for t in trig if t.cause == "departure probe"]
    if probes:
        def split(pred):
            ts = [t for t in probes if pred(t.extra["probe"])]
            return f"{len(ts)} (kept {sum(t.verdict == 'keep' for t in ts)})"
        print(f"  probe departures on one position (margin 150): {split(lambda p: p['margin'] == '150')}, "
              f"on two in a row: {split(lambda p: p['margin'] != '150')}, "
              f"position worse than 200 m: {split(lambda p: int(p['acc']) > 200)}")
    for t in sorted((t for t in trig if t.verdict == "keep"), key=lambda t: t.opened):
        fr = f"{t.first_reading[0]} {t.first_reading[1]:.0f}s later" if t.first_reading else "no reading"
        print(f"    kept: {t.opened:%m-%d %H:%M} track {t.id} ({t.cause}) {t.pts} pts {t.dist} m, then {fr}")
    if r.probe_durations:
        durations = sorted(r.probe_durations)
        total = sum(durations)
        print(f"  probes: {len(durations)} ({r.per_day(len(durations)):.0f}/day), "
              f"median {pct(durations, .5):.0f}s, p90 {pct(durations, .9):.0f}s, "
              f"max {durations[-1]:.0f}s, total {hours(total)} ({hours(r.per_day(total))}/day)")
        per_dep = f", {r.watch_n / len(trig):.0f} per departure" if trig else ""
        print(f"  positions judged: {r.watch_n} ({r.per_day(r.watch_n):.0f}/day{per_dep}); probes that never saw "
              f"20 m of movement: {r.probes_still} of {r.probes_with_positions}")
    print(f"  motion trigger fired {r.fires}× ({r.per_day(r.fires):.0f}/day): " +
          ", ".join(f"{o} {n}" for o, n in r.fire_outcomes.most_common()))
    if r.fence_sources:
        accs = f", median arming accuracy {pct(sorted(r.fence_accs), .5)} m" if r.fence_accs else ""
        print("  fence armed from last known: " + ", ".join(f"{p} {n}" for p, n in r.fence_sources.items()) + accs)

    section("Recorder GPS" + ("" if r.exact_gps else " (inferred: this build logs no stop)"))
    total = r.gps_total()
    with_pts = sum(t.gps_s for t in closed if t.pts)
    zero = sum(t.gps_s for t in closed if t.pts == 0)
    print(f"  {hours(total)} in total, {hours(r.per_day(total))}/day")
    print(f"  on tracks that recorded points {hours(with_pts)}, on tracks with 0 points {hours(zero)}, "
          f"elsewhere {hours(max(0.0, total - with_pts - zero))}")
    print(f"  no-fix give-ups {r.gave_up} ({r.per_day(r.gave_up):.0f}/day); GPS stretches ended by one "
          f"{r.gps_gave_up_n}, {hours(r.gps_gave_up_s)}; restarts after a give-up: " +
          (", ".join(f"{s} {n}" for s, n in r.retries.most_common()) or "none"))

    section("Dropped fixes (no recent GNSS backing)")
    bands = collections.Counter(
        "unknown" if a is None else "<10 m" if a < 10 else "10-50 m" if a < 50 else "50-200 m" if a < 200
        else "200 m+" for a in r.drops)
    print(f"  {len(r.drops)} ({r.per_day(len(r.drops)):.0f}/day); by accuracy: " +
          ", ".join(f"{b} {bands[b]}" for b in ("<10 m", "10-50 m", "50-200 m", "200 m+", "unknown") if bands[b]))
    print(f"  on tracks that were kept: {sum(t.drops for t in closed if t.verdict == 'keep')}; "
          f"on tracks left with 0 points: {sum(t.drops for t in closed if t.pts == 0)}")

    section("Recorder health")
    waits = r.holds
    if waits:
        print(f"  held readings {len(waits)}: over 60 s {sum(w > 60 for w in waits)}, over 5 min "
              f"{sum(w > 300 for w in waits)}, longest {minutes(max(waits))}")
    print(f"  deaf re-registrations {r.deaf}, arrival closes {r.arrivals}, carrier renames {r.carrier}, "
          f"relabels {r.relabels}")
    stuck = sorted((t for t in closed if t.pts == 0 and t.dur > 1800), key=lambda t: -t.dur)
    print(f"  tracks open over 30 min with 0 points: {len(stuck)}")
    for t in stuck[:8]:
        opened = f"{t.opened:%m-%d %H:%M}" if t.opened else "?"
        print(f"    {opened} track {t.id} {t.act} ({t.cause}) {minutes(t.dur)}")

    section("Activity changes that split a moving track")
    pairs = collections.defaultdict(list)
    for t in r.tracks.values():
        if t.cause == "Activity Recognition mid-track" and t.extra.get("pair"):
            pairs[t.extra["pair"]].append(t)
    rows = [(p, len(ts), sum(t.verdict == "keep" for t in ts), sum(t.pts == 0 for t in ts if t.verdict))
            for p, ts in sorted(pairs.items(), key=lambda x: -len(x[1]))[:12]]
    table(("from → to", "tracks", "kept", "0 pts"), rows)

    if daily:
        section("Per day")
        rows = []
        for day in sorted(r.daily):
            dd = r.daily[day]
            rows.append((day, dd["opened"], dd["keep"], dd["zero"], f"{dd['gps_s'] / 3600:.1f}", dd["gave_up"],
                         dd["fires"], dd["probes"], f"{dd['probe_s'] / 3600:.1f}", dd["departures"], dd["drops"]))
        table(("day", "opened", "kept", "0 pts", "GPS h", "give-ups", "fires", "probes", "probe h", "departures",
               "drops"), rows)

    section("Lines this script does not recognise")
    print(f"  {sum(r.unrecognised.values())}")
    for s, n in r.unrecognised.most_common(8):
        print(f"    {n:>5}  {s}")


def headline(r):
    closed = r.closed_tracks()
    trig = r.triggered()
    gps = r.gps_total()
    zero_gps = sum(t.gps_s for t in closed if t.pts == 0)
    return [
        ("days with lines", r.days),
        ("tracks opened /day", f"{r.per_day(len(r.tracks)):.1f}"),
        ("kept share", f"{sum(t.verdict == 'keep' for t in closed) / max(1, len(closed)):.0%}"),
        ("0-point share", f"{sum(t.pts == 0 for t in closed) / max(1, len(closed)):.0%}"),
        ("recorder GPS h/day", f"{gps / 3600 / r.days:.1f}"),
        ("GPS on 0-point tracks", f"{zero_gps / max(1, gps):.0%}"),
        ("give-ups /day", f"{r.per_day(r.gave_up):.1f}"),
        ("departures /day", f"{r.per_day(len(trig)):.1f}"),
        ("departures kept", sum(t.verdict == "keep" for t in trig)),
        ("motion fires /day", f"{r.per_day(r.fires):.0f}"),
        ("probe h/day", f"{sum(r.probe_durations) / 3600 / r.days:.1f}"),
        ("positions judged /day", f"{r.per_day(r.watch_n):.0f}"),
        ("dropped fixes /day", f"{r.per_day(len(r.drops)):.0f}"),
        ("held readings over 5 min", sum(s > 300 for s in r.holds)),
        ("deaf re-registrations", r.deaf),
        ("service found dead", len(r.deaths)),
    ]


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("log")
    ap.add_argument("--compare", metavar="LOG", help="a second log to set beside the first, per day")
    ap.add_argument("--since", metavar="MM-DD")
    ap.add_argument("--until", metavar="MM-DD")
    ap.add_argument("--daily", action="store_true", help="add a per-day table")
    ap.add_argument("--year", type=int, default=datetime.date.today().year, help="year of the log's first line")
    args = ap.parse_args()

    reports = []
    for path in [args.log] + ([args.compare] if args.compare else []):
        events, unrecognised = parse(path, args.year, args.since, args.until)
        if not events:
            sys.exit(f"{path}: no log lines in range")
        reports.append(Report(path, events, unrecognised))

    if args.compare:
        a, b = (headline(r) for r in reports)
        table(("", reports[0].path, reports[1].path), [(k, va, vb) for (k, va), (_k, vb) in zip(a, b)])
        return
    report(reports[0], args.daily)


if __name__ == "__main__":
    main()
