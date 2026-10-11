#!/usr/bin/env python3
"""Every dog park in Poland → data/parks.json.

    python scraper/build.py                 # Overpass → data/osm-parks.json → data/parks.json
    python scraper/build.py --raw dump.json # from a saved Overpass answer, no network
    python scraper/build.py --offline       # only re-apply submissions and corrections

One Overpass query returns both the parks (with geometry, for the area and the pin)
and, for each of them, the admin areas it lies in, so the app can say "Rybnik,
Maroko-Nowiny" without shipping a gazetteer. On top go the submissions from the app
that passed moderation (manual/zgloszenia.json), then the hand-written corrections
(manual/poprawki.json, which win). The result is checked against scraper/CONTRACT.md
and only a file that passes is written.
"""

from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))

import contract  # noqa: E402
import overpass  # noqa: E402
from manual import apply_manual  # noqa: E402
from normalize import helper_from, park_from  # noqa: E402
from zgloszenia import community_layer  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "data" / "parks.json"
# OSM alone, before any correction: what --offline starts from, so the moderation run
# can republish after every batch of submissions without asking Overpass again.
OSM_OUT = ROOT / "data" / "osm-parks.json"
VETS_OUT = ROOT / "data" / "vets.json"
MIN_VETS = 1000  # 2 185 vets in OSM in October 2026
MANUAL = ROOT / "manual" / "poprawki.json"
COMMUNITY = ROOT / "manual" / "zgloszenia.json"

QUERY = """
[out:json][timeout:900];
area["ISO3166-1"="PL"][admin_level=2]->.pl;
(nwr["leisure"="dog_park"](area.pl); nwr["amenity"~"^(veterinary|animal_shelter)$"](area.pl);)->.parks;
.parks out tags geom;
foreach.parks->.p(
  (node.p; node(w.p); way(r.p); node(w);)->.pts;
  .pts is_in->.a;
  area.a[boundary=administrative][admin_level=4]->.l4;
  area.a[boundary=administrative][admin_level=7]->.l7;
  area.a[boundary=administrative][admin_level=8]->.l8;
  area.a[boundary=administrative][admin_level=9]->.l9;
  area.a[boundary=administrative][admin_level=10]->.l10;
  make adm ref=p.u(type()+"/"+id()),
           wojewodztwo=l4.u(t["name"]), gmina=l7.u(t["name"]),
           miejscowosc=l8.u(t["name"]), dzielnica=l9.u(t["name"]),
           osiedle=l10.u(t["name"]);
  out;
);
"""

# Fewer than this many parks for the whole country means a truncated answer, not a
# sudden wave of closures, so the old file stays. OSM had 630 in October 2026.
MIN_PARKS = 400


def parks_from(raw: dict[str, Any]) -> list[dict[str, Any]]:
    elements = raw.get("elements") or []
    adm = {
        e["tags"]["ref"]: e["tags"]
        for e in elements
        if e.get("type") == "adm" and (e.get("tags") or {}).get("ref")
    }
    parks = []
    for el in elements:
        if el.get("type") not in {"node", "way", "relation"}:
            continue
        park = park_from(el, adm.get(f"{el['type']}/{el['id']}"))
        if park:
            parks.append(park)
    return parks


def helpers_from(raw: dict[str, Any]) -> list[dict[str, Any]]:
    elements = raw.get("elements") or []
    adm = {e["tags"]["ref"]: e["tags"] for e in elements
           if e.get("type") == "adm" and (e.get("tags") or {}).get("ref")}
    out = []
    for el in elements:
        if el.get("type") in {"node", "way", "relation"}:
            h = helper_from(el, adm.get(f"{el['type']}/{el['id']}"))
            if h:
                out.append(h)
    return out


def write_helpers(raw: dict[str, Any]) -> int:
    """data/vets.json, written only when it looks complete and passes its contract."""
    places = sorted(helpers_from(raw), key=lambda p: p["id"])
    vets = sum(1 for p in places if p["kind"] == "weterynarz")
    if vets < MIN_VETS:
        print(f"[error] only {vets} vets (< {MIN_VETS}); keeping the old vets.json", file=sys.stderr)
        return 1
    doc = {"generated_at": datetime.now().replace(microsecond=0).isoformat(),
           "osm_base": (raw.get("osm3s") or {}).get("timestamp_osm_base"),
           "count": len(places), "places": places}
    problems = contract.check_helpers(doc)
    if problems:
        for p in problems[:30]:
            print(f"[error] vets contract: {p}", file=sys.stderr)
        return 1
    write_list(VETS_OUT, {k: v for k, v in doc.items() if k != "places"}, places, key="places")
    print(f"[info] wrote {VETS_OUT.relative_to(ROOT)}: {vets} vets, {len(places) - vets} shelters, "
          f"{sum(1 for p in places if p['allDay'])} open day and night", file=sys.stderr)
    return 0


def write_list(path: Path, head: dict[str, Any], parks: list[dict[str, Any]], key: str = "parks") -> None:
    """One park per line: a refresh diffs as "these parks changed" instead of one huge
    line, and the file stays readable on GitHub."""
    lines = ",\n".join("    " + json.dumps(p, ensure_ascii=False, separators=(",", ":")) for p in parks)
    text = json.dumps(head, ensure_ascii=False, indent=2)[:-2] + f',\n  "{key}": [\n' + lines + "\n  ]\n}\n"
    json.loads(text)  # the hand-built layout must still be valid JSON
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def publish(osm_parks: list[dict[str, Any]], osm_base: str | None) -> int:
    """OSM, then the community's submissions, then the hand-written corrections on top
    → data/parks.json, written only when it passes the contract."""
    failures: list[dict[str, str]] = []
    community = json.loads(COMMUNITY.read_text(encoding="utf-8")) if COMMUNITY.exists() else {}
    parks = community_layer([dict(p) for p in osm_parks], community)
    manual = json.loads(MANUAL.read_text(encoding="utf-8")) if MANUAL.exists() else {}
    parks, manual_failures = apply_manual(parks, manual)
    failures += manual_failures

    parks.sort(key=lambda p: (p["region"] or "~", p["locality"] or "~", p["name"] or "~", p["id"]))
    doc = {
        "generated_at": datetime.now().replace(microsecond=0).isoformat(),
        "osm_base": osm_base,
        "count": len(parks),
        "failures": failures,
        "parks": parks,
    }
    problems = contract.check(doc)
    if problems:
        for p in problems[:30]:
            print(f"[error] contract: {p}", file=sys.stderr)
        print(f"[error] {len(problems)} contract violations; nothing written", file=sys.stderr)
        return 1
    write_list(OUT, {k: v for k, v in doc.items() if k != "parks"}, parks)

    named = sum(1 for p in parks if p["name"])
    placed = sum(1 for p in parks if p["locality"])
    community_count = sum(1 for p in parks if p["source"] == "zgloszenie")
    print(f"[info] wrote {OUT.relative_to(ROOT)}: {len(parks)} parks, {named} named, "
          f"{placed} with a locality, {community_count} from the app, {len(failures)} failures",
          file=sys.stderr)
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--raw", type=Path, help="saved Overpass answer instead of a live query")
    ap.add_argument("--save-raw", type=Path, help="also save the Overpass answer here")
    ap.add_argument("--offline", action="store_true",
                    help="reuse data/osm-parks.json: re-apply submissions and corrections only")
    ap.add_argument("--min-parks", type=int, default=MIN_PARKS)
    args = ap.parse_args()

    if args.offline:
        osm = json.loads(OSM_OUT.read_text(encoding="utf-8"))
        return publish(osm["parks"], osm.get("osm_base"))

    if args.raw:
        raw = json.loads(args.raw.read_text(encoding="utf-8"))
    else:
        raw = overpass.run(QUERY)
        if args.save_raw:
            args.save_raw.write_text(json.dumps(raw, ensure_ascii=False), encoding="utf-8")

    parks = parks_from(raw)
    print(f"[info] {len(parks)} parks from OSM", file=sys.stderr)
    if len(parks) < args.min_parks:
        print(f"[error] only {len(parks)} parks (< {args.min_parks}); keeping the old file",
              file=sys.stderr)
        return 1
    osm_base = (raw.get("osm3s") or {}).get("timestamp_osm_base")
    parks.sort(key=lambda p: p["id"])
    write_list(OSM_OUT, {"osm_base": osm_base, "count": len(parks)}, parks)
    # Vets are a separate file the app reads on its own: a failure there must not keep
    # the parks back, but it does make the run red.
    vets_status = write_helpers(raw)
    return publish(parks, osm_base) or vets_status


if __name__ == "__main__":
    sys.exit(main())
