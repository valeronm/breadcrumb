#!/usr/bin/env python3
"""Pack Natural Earth's country outlines into the binary borders file the app ships beside the atlas.

Source: https://raw.githubusercontent.com/nvkelso/natural-earth-vector/v5.1.2/geojson/ne_10m_admin_0_map_units.geojson
(public domain). The map-units edition rather than the countries one because GeoNames codes overseas
regions on their own (French Guiana, Réunion), which the countries edition folds into their state's
outline.

    ./tools/pack_borders.py ne_10m_admin_0_map_units.geojson app/src/main

Reads the packed atlas from `<app/src/main>/assets/cities.bin`, so it runs after `pack_cities.py`, and
again every time that does: the file records the atlas row count it was labeled against, and the app
refuses a pair packed apart.

Coasts are simplified and land borders are not. A vertex two outlines share stays exact, which keeps
every border at the source's precision and its two sides identical. Moving a coast by up to
COAST_TOLERANCE moves no border, and roughly halves the file.

An outline's code yields to the atlas's where the two disagree outright: an outline none of whose
atlas places carries its code takes the code most of them carry. The app names a spot after a place of
its outline's code, so the two must use one vocabulary. They differ in places: Natural Earth draws
borders as they stand on the ground and leaves some areas uncoded, while GeoNames codes each place by
the state it recognizes. An outline holding even one place of its own code keeps it, since a small one
(Monaco, Macau) can hold more border towns plotted a little inside it than places of its own. Every
relabel is printed. An outline still uncoded after that holds no atlas place, and is left out.

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
of its own.
"""

import json
import os
import struct
import sys
from collections import Counter, defaultdict
from dataclasses import dataclass

from pack_cities import HEADER, ROW, write
from pack_cities import MAGIC as ATLAS_MAGIC

MAGIC = b"BCBR1"
SCALE = 1e5
# Degrees: about 1.1 km of latitude and less of longitude away from the equator, which a coast can afford.
COAST_TOLERANCE = 0.01
# Natural Earth and GeoNames disagree mostly about places within a kilometer or two of a border, so a
# share of atlas places in an outline of their own code below this means the wrong file or field was
# read. It is taken before relabeling, which makes nearly every place agree whatever was read.
OWN_CODE_FLOOR = 0.95
# Two datasets that agree relabel a handful of outlines; more than this means the codes were misread.
MAX_RELABELS = 25
# Degrees of latitude per bucket of locate()'s edge index.
BAND = 0.02


@dataclass
class Outline:
    code: str | None
    name: str
    rings: list


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
    return [(lat / 1e6, lon / 1e6, country.decode("ascii")) for lat, lon, _, country, _, _ in rows]


def code_of(properties):
    """Natural Earth writes -99 where a unit has no code, and ISO_A2_EH fills most of the gaps ISO_A2
    leaves."""
    for key in ("ISO_A2_EH", "ISO_A2"):
        value = properties.get(key) or ""
        if len(value) == 2 and value.isascii() and value.isalpha() and value.isupper():
            return value
    return None


def read_outlines(path):
    """An Outline per polygon, rings as (lon, lat) tuples without the closing repeat, and the vertices
    more than one unit shares."""
    outlines = []
    owners = defaultdict(set)
    for unit, feature in enumerate(json.load(open(path, encoding="utf-8"))["features"]):
        geometry = feature["geometry"]
        polygons = [geometry["coordinates"]] if geometry["type"] == "Polygon" else geometry["coordinates"]
        code = code_of(feature["properties"])
        name = feature["properties"].get("NAME", "?")
        for polygon in polygons:
            rings = []
            for ring in polygon:
                points = [tuple(p) for p in ring]
                if points[0] == points[-1]:
                    points.pop()
                for p in points:
                    owners[p].add(unit)
                rings.append(points)
            outlines.append(Outline(code, name, rings))
    shared = {p for p, units in owners.items() if len(units) > 1}
    return outlines, shared


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
        out.append(Outline(outline.code, outline.name, [ring for ring in thin if len(ring) >= 3]))
    return out


def locate(outlines, rows):
    """The atlas's country codes counted per index of the outline holding each row, and how many rows
    no outline holds. Edges are bucketed by latitude because every row against every ring would take
    hours."""
    bands = defaultdict(list)
    for index, outline in enumerate(outlines):
        for ring in outline.rings:
            for (x1, y1), (x2, y2) in zip(ring, ring[1:] + ring[:1]):
                if y1 == y2:
                    continue
                for band in range(int((min(y1, y2) + 90) // BAND), int((max(y1, y2) + 90) // BAND) + 1):
                    bands[band].append((x1, y1, x2, y2, index))
    places = defaultdict(Counter)
    none = 0
    for lat, lon, country in rows:
        crossings = Counter()
        for x1, y1, x2, y2, index in bands.get(int((lat + 90) // BAND), ()):
            if (y1 > lat) != (y2 > lat) and lon < (x2 - x1) * (lat - y1) / (y2 - y1) + x1:
                crossings[index] += 1
        inside = [index for index, n in crossings.items() if n % 2]
        # The app takes the first outline holding a point, so an overlap would leave its country to
        # file order.
        if len(inside) > 1:
            names = ", ".join(outlines[index].name for index in inside)
            raise SystemExit(f"an atlas place at {lat}, {lon} sits in more than one outline: {names}")
        if inside:
            places[inside[0]][country] += 1
        else:
            none += 1
    return places, none


def relabel(outlines, places):
    """The module docstring's rule, applied to [outlines] in place; returns how many it changed."""
    changed = 0
    for index, counts in sorted(places.items()):
        outline = outlines[index]
        if outline.code in counts:
            continue
        winner = min(counts.items(), key=lambda item: (-item[1], item[0]))[0]
        print(f"relabel {outline.name} ({outline.code or 'uncoded'}) -> {winner}: places inside {dict(counts)}")
        outline.code = winner
        changed += 1
    return changed


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
    if len(argv) != 3:
        raise SystemExit(f"usage: {argv[0]} <ne_10m_admin_0_map_units.geojson> <app/src/main>")
    rows = read_atlas(os.path.join(argv[2], "assets", "cities.bin"))
    source, shared = read_outlines(argv[1])
    outlines = simplified(source, shared)
    vertices = sum(len(ring) for outline in outlines for ring in outline.rings)
    print(f"{len(outlines):,} outlines, {vertices:,} vertices ({len(shared):,} on shared borders)")
    places, none = locate(outlines, rows)
    share = agreement(outlines, places, none, "before relabeling")
    if share < OWN_CODE_FLOOR:
        raise SystemExit(f"only {share:.2%} of atlas places sit in an outline of their own code, under {OWN_CODE_FLOOR:.0%}")
    relabeled = relabel(outlines, places)
    if relabeled > MAX_RELABELS:
        raise SystemExit(f"{relabeled} outlines relabeled, over {MAX_RELABELS}")
    agreement(outlines, places, none, "after relabeling")
    for outline in outlines:
        if not outline.code:
            print(f"leave out {outline.name}: uncoded, with no atlas place inside")
    coded = [outline for outline in outlines if outline.code]
    write(os.path.join(argv[2], "assets", "borders.bin"), pack(len(rows), coded), f"{len(coded):,} outlines")


if __name__ == "__main__":
    main(sys.argv)
