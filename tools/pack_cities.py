#!/usr/bin/env python3
"""Pack a GeoNames city dump into the binary atlas the app ships.

Source: https://download.geonames.org/export/dump/cities1000.zip (CC BY 4.0) — every populated place
of 1,000 people or more *plus every seat of an administrative division whatever its size*, which is
the reason for this file rather than the smaller cities5000: a historic village of a few hundred is
often its own seat, and it is exactly the kind of place a journey is named after. The dump is 35 MB
of tab-separated text with 19 columns, of which the app needs five; this writes those five as a
fixed-width table sorted by latitude, which is what lets the lookup binary-search a coordinate
instead of scanning 160,000 rows.

    ./tools/pack_cities.py cities1000.txt app/src/main/assets/cities.bin

The output is checked into the repo. Regenerate it only to take a newer dump — a fresh checkout and
CI must never need the network for it.

Format (big-endian, matching java.nio.ByteBuffer's default so the reader needs no byte-order call):

    magic       5 bytes  "BCTY1"
    rowCount    int32
    tzCount     uint16
    tz ids      tzCount x (uint8 length + UTF-8 bytes)      # IANA ids, e.g. "Europe/Lisbon"
    rows        rowCount x 15 bytes, ascending by latitude:
                    lat     int32   microdegrees
                    lon     int32   microdegrees
                    popK    uint16  population / 1000, saturating
                    country 2 bytes ISO 3166-1 alpha-2
                    tz      uint16  index into the tz table
                    nameLen uint8   UTF-8 byte length of the name
    names       concatenated UTF-8, in row order

Names carry no offsets: the reader accumulates the lengths in one pass at load, which costs a
microsecond and saves 270 KB of asset.
"""

import struct
import sys

MAGIC = b"BCTY1"
# GeoNames dump columns, of the 19 the format defines.
COL_NAME, COL_LAT, COL_LON, COL_FEATURE, COL_COUNTRY, COL_POP, COL_TZ = 1, 4, 5, 7, 8, 14, 17
MAX_NAME_BYTES = 255
MAX_POP_K = 0xFFFF

# Places that can name somewhere a person stayed. The dump also carries PPLX — "section of populated
# place" — which is what makes a naive nearest-row lookup answer central Lisbon with a neighbourhood
# of 5,000 and central Paris with an arrondissement instead of the cities they sit in. The rest of
# the exclusions (PPLH, PPLQ, PPLW, PPLCH) are places that no longer exist: historical, abandoned,
# destroyed. Nothing that happened last week should be named after a ruin.
KEEP_FEATURES = {
    "PPL", "PPLA", "PPLA2", "PPLA3", "PPLA4", "PPLA5", "PPLC",
    "PPLF", "PPLG", "PPLL", "PPLR", "PPLS", "STLMT",
}


def read_rows(path):
    rows = []
    for line_no, line in enumerate(open(path, encoding="utf-8"), 1):
        f = line.rstrip("\n").split("\t")
        if len(f) < 19:
            raise SystemExit(f"{path}:{line_no}: expected 19 columns, got {len(f)}")
        if f[COL_FEATURE] not in KEEP_FEATURES:
            continue
        name = f[COL_NAME].encode("utf-8")
        if not name or len(name) > MAX_NAME_BYTES:
            raise SystemExit(f"{path}:{line_no}: name of {len(name)} bytes does not fit")
        country = f[COL_COUNTRY].encode("ascii")
        if len(country) != 2:
            # A handful of GeoNames rows carry no country (disputed or international zones).
            continue
        rows.append(
            (
                round(float(f[COL_LAT]) * 1e6),
                round(float(f[COL_LON]) * 1e6),
                min(int(f[COL_POP] or 0) // 1000, MAX_POP_K),
                country,
                f[COL_TZ],
                name,
            )
        )
    rows.sort(key=lambda r: r[0])
    return rows


def pack(rows):
    zones = sorted({r[4] for r in rows})
    zone_index = {z: i for i, z in enumerate(zones)}
    out = bytearray(MAGIC)
    out += struct.pack(">i", len(rows))
    out += struct.pack(">H", len(zones))
    for zone in zones:
        encoded = zone.encode("utf-8")
        out += struct.pack(">B", len(encoded)) + encoded
    names = bytearray()
    for lat, lon, pop_k, country, zone, name in rows:
        out += struct.pack(">iiH", lat, lon, pop_k)
        out += country
        out += struct.pack(">HB", zone_index[zone], len(name))
        names += name
    return bytes(out + names)


def main(argv):
    if len(argv) != 3:
        raise SystemExit(f"usage: {argv[0]} <cities5000.txt> <out.bin>")
    rows = read_rows(argv[1])
    blob = pack(rows)
    with open(argv[2], "wb") as out:
        out.write(blob)
    zones = len({r[4] for r in rows})
    print(f"{len(rows):,} cities, {zones} time zones -> {argv[2]} ({len(blob) / 1e6:.2f} MB)")


if __name__ == "__main__":
    main(sys.argv)
