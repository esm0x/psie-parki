"""The same answer the Overpass query gives, built from OSM extract files instead.

    python scraper/pbf.py extracts/*.osm.pbf -o raw.json
    python scraper/build.py --raw raw.json

For the days Overpass is down, and for machines that cannot reach it. Reads one or more
.osm.pbf files (Poland whole, or its voivodeships from download.openstreetmap.fr or
Geofabrik), finds every leisure=dog_park and the admin areas each one lies in, and
writes JSON shaped like Overpass's `out tags geom` plus the `adm` elements of the
query in build.py, so build.py does not care where the data came from.

Needs `pip install osmium shapely`; the weekly CI job uses Overpass and does not.
"""

from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import osmium
from shapely import wkb
from shapely.geometry import Point
from shapely.strtree import STRtree

LEVELS = {"4": "wojewodztwo", "7": "gmina", "8": "miejscowosc", "9": "dzielnica", "10": "osiedle"}


def is_park(tags: osmium.osm.TagList) -> bool:
    return tags.get("leisure") == "dog_park"


def is_admin(tags: osmium.osm.TagList) -> bool:
    return tags.get("boundary") == "administrative" and tags.get("admin_level") in LEVELS and "name" in tags


def read(path: Path, parks: dict[str, dict[str, Any]], admin: list[tuple[str, str, Any]]) -> str | None:
    """Collects parks and admin polygons from one file; returns the file's timestamp."""
    factory = osmium.geom.WKBFactory()
    fp = (
        osmium.FileProcessor(str(path))
        .with_locations()
        .with_areas(osmium.filter.KeyFilter("leisure", "boundary"))
        .with_filter(osmium.filter.KeyFilter("leisure", "boundary"))
    )
    for obj in fp:
        tags = obj.tags
        if isinstance(obj, osmium.osm.Node):
            if is_park(tags):
                parks[f"node/{obj.id}"] = {
                    "type": "node", "id": obj.id, "lat": obj.location.lat, "lon": obj.location.lon,
                    "tags": dict(tags),
                }
        elif isinstance(obj, osmium.osm.Area):
            if is_park(tags):
                kind = "way" if obj.from_way() else "relation"
                oid = obj.orig_id()
                rings = []
                for outer in obj.outer_rings():
                    rings.append([{"lat": n.lat, "lon": n.lon} for n in outer])
                if not rings:
                    continue
                el: dict[str, Any] = {"type": kind, "id": oid, "tags": dict(tags)}
                if kind == "way":
                    el["geometry"] = rings[0]
                else:
                    el["members"] = [{"type": "way", "role": "outer", "geometry": r} for r in rings]
                parks[f"{kind}/{oid}"] = el
            elif is_admin(tags):
                try:
                    poly = wkb.loads(factory.create_multipolygon(obj), hex=True)
                except RuntimeError:
                    continue  # a broken boundary relation; the next level up still applies
                admin.append((LEVELS[tags["admin_level"]], tags["name"], poly))
    header = osmium.io.Reader(str(path), osmium.osm.osm_entity_bits.NOTHING).header()
    return header.get("osmosis_replication_timestamp") or header.get("timestamp") or None


def centre(el: dict[str, Any]) -> tuple[float, float]:
    if el["type"] == "node":
        return el["lat"], el["lon"]
    pts = el.get("geometry") or [p for m in el["members"] for p in m["geometry"]]
    lats = [p["lat"] for p in pts]
    lons = [p["lon"] for p in pts]
    return (min(lats) + max(lats)) / 2, (min(lons) + max(lons)) / 2


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("files", nargs="+", type=Path)
    ap.add_argument("-o", "--out", type=Path, required=True)
    args = ap.parse_args()

    parks: dict[str, dict[str, Any]] = {}
    admin: list[tuple[str, str, Any]] = []
    stamps = []
    for f in args.files:
        before = len(parks)
        stamp = read(f, parks, admin)
        stamps.append(stamp)
        print(f"[info] {f.name}: {len(parks) - before} new parks, {len(admin)} admin areas so far",
              file=sys.stderr)

    # A gmina on a voivodeship border sits in two extracts; keep one copy per name+level.
    seen, unique = set(), []
    for level, name, poly in admin:
        key = (level, name, round(poly.area, 9))
        if key not in seen:
            seen.add(key)
            unique.append((level, name, poly))
    tree = STRtree([p for _, _, p in unique])

    elements: list[dict[str, Any]] = list(parks.values())
    for key, el in parks.items():
        lat, lon = centre(el)
        pt = Point(lon, lat)
        tags = {"ref": key}
        for i in tree.query(pt, predicate="intersects"):
            level, name, _ = unique[i]
            tags.setdefault(level, name)
        elements.append({"type": "adm", "id": 0, "tags": tags})

    stamp = max((s for s in stamps if s), default=None) or datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    out = {"osm3s": {"timestamp_osm_base": stamp, "source": "pbf"}, "elements": elements}
    args.out.write_text(json.dumps(out, ensure_ascii=False), encoding="utf-8")
    print(f"[info] {len(parks)} parks, {len(unique)} admin areas → {args.out}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
