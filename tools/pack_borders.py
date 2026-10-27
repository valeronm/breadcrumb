#!/usr/bin/env python3
"""Pack OpenStreetMap's country outlines into the binary borders file the app ships beside the atlas.

Source: https://github.com/simonepri/geo-maps/releases/download/v0.6.0/countries-maritime-100m.geo.json
(© OpenStreetMap contributors, ODbL), its three-letter codes turned into the atlas's two-letter ones by
GeoNames' own table, https://download.geonames.org/export/dump/countryInfo.txt. That release is
geo-maps' last, a 2017 snapshot of OpenStreetMap's borders.

    ./tools/pack_borders.py countries-maritime-100m.geo.json countryInfo.txt app/src/main

Reads the packed atlas from `<app/src/main>/assets/cities.bin`, so it runs after `pack_cities.py`, and
again every time that does: the file records the atlas row count it was labeled against, and the app
refuses a pair packed apart.

The maritime edition carries each country out to its territorial sea, so a beach or a harbor sits inside
its country rather than beside a simplified coastline. At 100 m its land borders keep the two
checkpoints of a border crossing apart. Natural Earth's 1:10m set runs kilometers off the ground in
places, putting whole border towns across the line.

geo-maps writes a country's holes as further polygons of that country, so a polygon inside another of
its own country is a hole in it. Countries' outlines overlap: a state draws its territories too (France
its overseas regions, China Hong Kong), and two states draw the ground both claim. The app takes the
first outline holding a point, so the file's order settles every overlap. An outline inside another
country's comes before it, and where outlines overlap otherwise, the one whose code most atlas places in
the overlap carry comes first. Where nothing else orders two outlines, a territory's comes before its
state's: a territory is a country another draws around every outline of. Of an outline two countries
draw alike, the later is left out, since it could never hold a point.

The edge of a country's sea is simplified and its borders with other countries are not. A vertex two
countries share stays exact, which keeps every border at the source's precision and its two sides
identical, and so does every vertex lying inside another country's outline, where overlapping outlines
meet without sharing one. Moving a sea's edge by up to COAST_TOLERANCE moves no border.

An outline's code yields to the atlas's where the two disagree outright: an outline none of whose atlas
places carries its code takes the code most of them carry, counting the places an earlier outline decides
too. An outline holding no places, around outlines of one other code, takes that code: a state draws its
territory's sea as one block around islands with no atlas place. The app names a spot after a place of its
outline's code, so the two must use one vocabulary. They differ in places: OpenStreetMap draws borders
as they stand on the ground, while GeoNames codes each place by the state it recognizes. An outline
holding even one place of its own code keeps it, since a small one (Monaco, Macau) can hold more border
towns plotted a little inside it than places of its own. Every relabel is printed. An outline still
uncoded after that holds no atlas place, and is left out.

Format (big-endian, matching java.nio.ByteBuffer's default):

    magic         5 bytes  "BCBR1"
    atlasRows     int32    the rows of the cities.bin it was labeled against
    outlineCount  int32
    outlines      outlineCount x:
                      country    2 bytes  ISO 3166-1 alpha-2
                      ringCount  varint
                      rings      ringCount x:
                                     vertexCount  varint
                                     vertices     vertexCount x (lat, lon), each coordinate in units of
                                                  1e-5 degrees as a zigzag varint delta from the vertex
                                                  before it, the ring's first from (0, 0)

Varints are unsigned LEB128. An outline is one polygon: a point is inside it when it is inside an odd
number of its rings, so a hole needs no marking — an enclave is a hole in its neighbor and an outline
of its own. Outlines are in precedence order: where two hold a point, the first decides.
"""

import heapq
import json
import os
import struct
import sys
from collections import Counter, defaultdict
from dataclasses import dataclass, replace

from pack_cities import HEADER, ROW, write
from pack_cities import MAGIC as ATLAS_MAGIC

MAGIC = b"BCBR1"
SCALE = 1e5
# Degrees: about 1.1 km of latitude and less of longitude away from the equator, which the edge of a
# territorial sea can afford.
COAST_TOLERANCE = 0.01
# OpenStreetMap and GeoNames disagree mostly about places near a border, so a share of atlas places inside
# some outline of their own code below this means the wrong file or field was read. It is taken before
# relabeling, which makes nearly every place agree whatever was read.
OWN_CODE_FLOOR = 0.95
# Two datasets that agree relabel a handful of outlines; more than this means the codes were misread.
MAX_RELABELS = 25
# Degrees of latitude per bucket of holders()'s edge index.
BAND = 0.02
# Scanlines across an outline, each giving a point inside it, for judging whether it lies inside another.
PROBES = 5


@dataclass
class Outline:
    code: str | None
    name: str
    rings: list
    key: int
    # Keys of the other countries' outlines lying inside this one.
    inner: frozenset
    territory: bool


def read_atlas(path):
    """The atlas's rows as (lat, lon, country)."""
    blob = open(path, "rb").read()
    if blob[: len(ATLAS_MAGIC)] != ATLAS_MAGIC:
        raise SystemExit(f"{path}: not a city atlas")
    row_count, zone_count = HEADER.unpack_from(blob, len(ATLAS_MAGIC))
    at = len(ATLAS_MAGIC) + HEADER.size
    for _ in range(zone_count):
        at += 1 + blob[at]
    rows = ROW.iter_unpack(blob[at: at + row_count * ROW.size])
    return [(lat / 1e6, lon / 1e6, country.decode("ascii")) for lat, lon, _, country, *_ in rows]


def read_codes(path):
    """GeoNames' countryInfo.txt as a map of ISO 3166-1 alpha-3 codes to alpha-2."""
    codes = {}
    for line in open(path, encoding="utf-8"):
        if line.startswith("#"):
            continue
        fields = line.split("\t")
        codes[fields[1]] = fields[0]
    return codes


def edges(ring):
    return zip(ring, ring[1:] + ring[:1])


def crossing_x(x1, y1, x2, y2, y):
    """Where the edge from (x1, y1) to (x2, y2) crosses the line at [y], for an edge that does."""
    return (x2 - x1) * (y - y1) / (y2 - y1) + x1


def crossings(rings, y):
    """Where the edges of [rings] cross the line at [y]."""
    for ring in rings:
        for (x1, y1), (x2, y2) in edges(ring):
            if (y1 > y) != (y2 > y):
                yield crossing_x(x1, y1, x2, y2, y)


def inside(rings, x, y):
    """Whether (x, y) is inside an odd number of [rings]."""
    return sum(x < cx for cx in crossings(rings, y)) % 2 == 1


def probes(rings):
    """Points inside the outline [rings], one per scanline: the middle of the widest stretch of the line
    the outline holds. A vertex would not do, since a hole or a neighbor shares its boundary."""
    _, low, _, high = box(rings)
    out = []
    for k in range(1, PROBES + 1):
        y = low + (high - low) * k / (PROBES + 1)
        xs = sorted(crossings(rings, y))
        stretches = [(xs[i], xs[i + 1]) for i in range(0, len(xs) - 1, 2)]
        if stretches:
            a, b = max(stretches, key=lambda s: s[1] - s[0])
            out.append(((a + b) / 2, y))
    return out


def within(points, rings):
    """Whether most of [points], an outline's [probes], lie inside the outline [rings]."""
    return bool(points) and sum(inside(rings, x, y) for x, y in points) * 2 > len(points)


def box(rings):
    xs = [p[0] for p in rings[0]]
    ys = [p[1] for p in rings[0]]
    return min(xs), min(ys), max(xs), max(ys)


def box_area(rings):
    west, south, east, north = box(rings)
    return (east - west) * (north - south)


def grouped(polygons):
    """A country's polygons as outlines, each polygon lying inside another attached to it as a hole."""
    outlines = []
    for polygon in sorted(polygons, key=box_area, reverse=True):
        points = probes(polygon)
        host = next((o for o in reversed(outlines) if within(points, o)), None)
        if host is None:
            outlines.append(list(polygon))
        else:
            host.extend(polygon)
    return outlines


def open_ring(ring):
    points = [tuple(p) for p in ring]
    if points[0] == points[-1]:
        points.pop()
    return points


def boxes_meet(a, b):
    return a[0] <= b[2] and b[0] <= a[2] and a[1] <= b[3] and b[1] <= a[3]


def read_outlines(path, codes):
    """The module docstring's outlines, rings as (lon, lat) tuples without the closing repeat; the vertices
    it keeps exact; and the pairs of keys of outlines two countries draw alike."""
    countries = []
    for feature in json.load(open(path, encoding="utf-8"))["features"]:
        geometry = feature["geometry"]
        polygons = [geometry["coordinates"]] if geometry["type"] == "Polygon" else geometry["coordinates"]
        rings = [[open_ring(ring) for ring in polygon] for polygon in polygons]
        name = feature["properties"]["A3"]
        countries.append((name, codes.get(name), grouped(rings)))
    units = [(country, rings, box(rings), probes(rings)) for country, (_, _, outlines) in enumerate(countries) for rings in outlines]
    # The other countries' outlines each one lies inside.
    holds = defaultdict(set)
    for i, (country_a, a, box_a, probes_a) in enumerate(units):
        for j in range(i + 1, len(units)):
            country_b, b, box_b, probes_b = units[j]
            if country_a == country_b or not boxes_meet(box_a, box_b):
                continue
            if within(probes_a, b):
                holds[i].add(j)
            if within(probes_b, a):
                holds[j].add(i)
    twins = sorted((i, j) for i in holds for j in holds[i] if i < j and i in holds.get(j, ()))
    inner = defaultdict(set)
    for i, outer in holds.items():
        for j in outer:
            if i not in holds.get(j, ()):
                inner[j].add(i)
    around = {}
    for index, (country, _, _, _) in enumerate(units):
        drawn_by = {units[j][0] for j in holds.get(index, ())}
        around[country] = around.get(country, drawn_by) & drawn_by
    outlines = [
        Outline(countries[country][1], countries[country][0], rings, index, frozenset(inner[index]), bool(around[country]))
        for index, (country, rings, _, _) in enumerate(units)
    ]
    return outlines, exact_vertices(outlines, [unit[0] for unit in units]), twins


def exact_vertices(outlines, country_of):
    """The vertices on a border, as the module docstring has them."""
    owners = defaultdict(set)
    for outline in outlines:
        for ring in outline.rings:
            for p in ring:
                owners[p].add(country_of[outline.key])
    exact = {p for p, countries in owners.items() if len(countries) > 1}
    loose = [p for p in owners if p not in exact]
    for (lon, lat), inside_of in zip(loose, holders(outlines, [(lat, lon) for lon, lat in loose])):
        (country,) = owners[(lon, lat)]
        if any(country_of[outlines[index].key] != country for index in inside_of):
            exact.add((lon, lat))
    return exact


def douglas_peucker(points, tolerance):
    if len(points) < 3:
        return points
    keep = [False] * len(points)
    keep[0] = keep[-1] = True
    stack = [(0, len(points) - 1)]
    while stack:
        a, b = stack.pop()
        (ax, ay), (bx, by) = points[a], points[b]
        dx, dy = bx - ax, by - ay
        length = (dx * dx + dy * dy) ** 0.5
        worst, at = -1.0, -1
        for i in range(a + 1, b):
            px, py = points[i]
            if length:
                d = abs(dy * (px - ax) - dx * (py - ay)) / length
            else:
                d = ((px - ax) ** 2 + (py - ay) ** 2) ** 0.5
            if d > worst:
                worst, at = d, i
        if worst > tolerance:
            keep[at] = True
            stack += [(a, at), (at, b)]
    return [p for p, k in zip(points, keep) if k]


def simplify(ring, shared):
    anchors = [i for i, p in enumerate(ring) if p in shared]
    if not anchors:
        x0, y0 = ring[0]
        far = max(range(len(ring)), key=lambda i: (ring[i][0] - x0) ** 2 + (ring[i][1] - y0) ** 2)
        anchors = [0, far] if far else [0]
    out = []
    for k, start in enumerate(anchors):
        end = anchors[(k + 1) % len(anchors)]
        run = ring[start: end + 1] if end > start else ring[start:] + ring[: end + 1]
        out += douglas_peucker(run, COAST_TOLERANCE)[:-1]
    return out


def simplified(outlines, shared):
    out = []
    for outline in outlines:
        thin = [simplify(ring, shared) for ring in outline.rings]
        if len(thin[0]) < 3:
            continue
        out.append(replace(outline, rings=[ring for ring in thin if len(ring) >= 3]))
    return out


def most_carried(counts):
    """The code most places carry, the alphabetically first of a tie."""
    return min(counts.items(), key=lambda item: (-item[1], item[0]))[0]


def holders(outlines, points):
    """For each (lat, lon) of [points], the indices of the outlines holding it. Edges are bucketed by
    latitude because every point against every ring would take hours."""
    bands = defaultdict(list)
    for index, outline in enumerate(outlines):
        for ring in outline.rings:
            for (x1, y1), (x2, y2) in edges(ring):
                if y1 == y2:
                    continue
                for band in range(int((min(y1, y2) + 90) // BAND), int((max(y1, y2) + 90) // BAND) + 1):
                    bands[band].append((x1, y1, x2, y2, index))
    held = []
    for lat, lon in points:
        crossed = Counter()
        for x1, y1, x2, y2, index in bands.get(int((lat + 90) // BAND), ()):
            if (y1 > lat) != (y2 > lat) and lon < crossing_x(x1, y1, x2, y2, lat):
                crossed[index] += 1
        held.append(sorted(index for index, n in crossed.items() if n % 2))
    return held


def ordered(outlines, rows, held, twins):
    """[outlines] in the module docstring's precedence order, without the later of each pair two countries
    draw alike."""
    position = {outline.key: index for index, outline in enumerate(outlines)}
    pairs = [(position[a], position[b]) for a, b in twins if a in position and b in position]
    alike = {frozenset(pair) for pair in pairs}
    after = defaultdict(set)
    for index, outline in enumerate(outlines):
        for key in outline.inner:
            if key in position:
                after[position[key]].add(index)
    votes = defaultdict(Counter)
    for (_, _, country), inside in zip(rows, held):
        if len(inside) > 1:
            votes[tuple(inside)][country] += 1
    for inside, counts in votes.items():
        winner = most_carried(counts)
        owners = [index for index in inside if outlines[index].code == winner]
        others = [index for index in inside if index not in owners]
        if frozenset(inside) not in alike and any(not set(others) <= after[w] for w in owners):
            names = " and ".join(outlines[index].name for index in inside)
            print(f"overlap of {names}: places inside {dict(counts)}, {winner} first")
        for w in owners:
            after[w].update(others)

    def entry(index):
        return not outlines[index].territory, index

    before = Counter(o for targets in after.values() for o in targets)
    ready = [entry(index) for index in range(len(outlines)) if not before[index]]
    heapq.heapify(ready)
    order = []
    while ready:
        _, index = heapq.heappop(ready)
        order.append(index)
        for o in after[index]:
            before[o] -= 1
            if not before[o]:
                heapq.heappush(ready, entry(o))
    if len(order) != len(outlines):
        stuck = ", ".join(sorted({outlines[i].name for i in set(range(len(outlines))) - set(order)}))
        raise SystemExit(f"no precedence order fits the overlaps of {stuck}")
    rank = {index: place for place, index in enumerate(order)}
    dropped, kept = set(), Counter()
    for a, b in pairs:
        first, later = sorted((a, b), key=rank.get)
        dropped.add(later)
        kept[(outlines[first].name, outlines[later].name)] += 1
    for (first, later), n in sorted(kept.items()):
        print(f"{first}: {n} outlines {later} draws alike, kept as {first}'s")
    return [outlines[index] for index in order if index not in dropped]


def locate(held, rows):
    """The atlas's country codes counted per index of the first outline holding each row, and how many
    rows no outline holds."""
    places = defaultdict(Counter)
    none = 0
    for (_, _, country), inside in zip(rows, held):
        if inside:
            places[inside[0]][country] += 1
        else:
            none += 1
    return places, none


def relabel(outlines, rows, held):
    """The module docstring's rule, applied to [outlines] in place; returns a line saying why for each
    outline it changed, by key."""
    places = defaultdict(Counter)
    for (_, _, country), inside in zip(rows, held):
        for index in inside:
            places[index][country] += 1
    code_of = {outline.key: outline.code for outline in outlines}
    changes = {}
    for index, outline in enumerate(outlines):
        counts = places.get(index)
        if counts:
            if outline.code in counts:
                continue
            winner, evidence = most_carried(counts), f"places inside {dict(counts)}"
        else:
            around = {code_of[key] for key in outline.inner if key in code_of}
            if len(around) != 1 or around == {outline.code} or around == {None}:
                continue
            (winner,) = around
            evidence = f"no places inside, around {winner}'s outlines"
        changes[outline.key] = f"relabel {outline.name} ({outline.code or 'uncoded'}) -> {winner}: {evidence}"
        outline.code = winner
    return changes


def own_code_share(outlines, rows, held):
    """The share of atlas places inside some outline of their own code, after printing the whole split."""
    own = other = none = 0
    for (_, _, country), inside in zip(rows, held):
        if not inside:
            none += 1
        elif any(outlines[index].code == country for index in inside):
            own += 1
        else:
            other += 1
    print(f"atlas places before relabeling: {own:,} in an outline of their own code, {other:,} only in another's, {none:,} in none")
    return own / (own + other + none)


def check_overlaps(outlines, rows, held):
    """Stops the packer wherever outlines overlap and the first holding the places there lacks the code
    most of them carry, though another of them has it."""
    votes = defaultdict(Counter)
    for (_, _, country), inside in zip(rows, held):
        if len(inside) > 1:
            votes[tuple(inside)][country] += 1
    wrong = []
    for inside, counts in votes.items():
        winner = most_carried(counts)
        if outlines[inside[0]].code != winner and any(outlines[index].code == winner for index in inside):
            wrong.append(f"{' and '.join(outlines[index].name for index in inside)} ({dict(counts)})")
    if wrong:
        raise SystemExit("the order puts the wrong outline first where " + "; ".join(wrong) + " overlap")


def agreement(outlines, places, none, when):
    """The share of atlas places in an outline of their own code, after printing the whole split."""
    own = sum(counts[outlines[index].code] for index, counts in places.items())
    other = sum(counts.total() for counts in places.values()) - own
    print(f"atlas places {when}: {own:,} in an outline of their own code, {other:,} of another, {none:,} in none")
    return own / (own + other + none)


def varint(value, out):
    while value >= 0x80:
        out.append((value & 0x7F) | 0x80)
        value >>= 7
    out.append(value)


def zigzag(value, out):
    varint((value << 1) ^ (value >> 63), out)


def pack(row_count, outlines):
    out = bytearray(MAGIC)
    out += struct.pack(">ii", row_count, len(outlines))
    for outline in outlines:
        out += outline.code.encode("ascii")
        varint(len(outline.rings), out)
        for ring in outline.rings:
            varint(len(ring), out)
            lat0 = lon0 = 0
            for lon, lat in ring:
                qlat, qlon = round(lat * SCALE), round(lon * SCALE)
                zigzag(qlat - lat0, out)
                zigzag(qlon - lon0, out)
                lat0, lon0 = qlat, qlon
    return bytes(out)


def main(argv):
    if len(argv) != 4:
        raise SystemExit(f"usage: {argv[0]} <countries-maritime-100m.geo.json> <countryInfo.txt> <app/src/main>")
    rows = read_atlas(os.path.join(argv[3], "assets", "cities.bin"))
    source, exact, twins = read_outlines(argv[1], read_codes(argv[2]))
    outlines = simplified(source, exact)
    vertices = sum(len(ring) for outline in outlines for ring in outline.rings)
    print(f"{len(outlines):,} outlines, {vertices:,} vertices ({len(exact):,} kept exact)")
    points = [(lat, lon) for lat, lon, _ in rows]
    held = holders(outlines, points)
    share = own_code_share(outlines, rows, held)
    if share < OWN_CODE_FLOOR:
        raise SystemExit(f"only {share:.2%} of atlas places sit in an outline of their own code, under {OWN_CODE_FLOOR:.0%}")
    # Before the order, whose votes compare places' codes with outlines'.
    changes = relabel(outlines, rows, held)
    outlines = ordered(outlines, rows, held, twins)
    # A state's copies of its territories are relabeled too, then dropped as twins.
    relabeled = [changes[outline.key] for outline in outlines if outline.key in changes]
    for line in relabeled:
        print(line)
    if len(relabeled) > MAX_RELABELS:
        raise SystemExit(f"{len(relabeled)} outlines relabeled, over {MAX_RELABELS}")
    held = holders(outlines, points)
    check_overlaps(outlines, rows, held)
    places, none = locate(held, rows)
    agreement(outlines, places, none, "in the file's order")
    for outline in outlines:
        if not outline.code:
            print(f"leave out {outline.name}: uncoded, with no atlas place inside")
    coded = [outline for outline in outlines if outline.code]
    write(os.path.join(argv[3], "assets", "borders.bin"), pack(len(rows), coded), f"{len(coded):,} outlines")


if __name__ == "__main__":
    main(sys.argv)
