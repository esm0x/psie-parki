"""data/parks.json against scraper/CONTRACT.md. The app deserializes it field by field,
so a wrong type here is a park missing there, or an app that shows nothing at all."""

from __future__ import annotations

import re
from typing import Any

ID = re.compile(r"^(node|way|relation)/\d+$|^manual/[a-z0-9-]+$")

STR_OR_NULL = ["name", "locality", "district", "region", "surface", "openingHours",
               "operator", "website", "description", "note"]
BOOL_OR_NULL = ["fenced", "lit", "water", "agility", "smallDogArea", "benches", "bins"]

# Poland with a margin: a park outside this box is a typo in manual/poprawki.json
# (lat and lon swapped is the usual one).
LAT = (48.9, 55.0)
LON = (14.0, 24.2)


def check(doc: dict[str, Any]) -> list[str]:
    problems: list[str] = []
    for key in ("generated_at", "count", "failures", "parks"):
        if key not in doc:
            problems.append(f"missing top-level {key!r}")
    parks = doc.get("parks") or []
    if doc.get("count") != len(parks):
        problems.append(f"count {doc.get('count')} != {len(parks)} parks")

    ids = set()
    for i, p in enumerate(parks):
        where = p.get("id", f"#{i}")
        if not isinstance(p.get("id"), str) or not ID.match(p["id"]):
            problems.append(f"{where}: bad id")
        if p.get("id") in ids:
            problems.append(f"{where}: duplicate id")
        ids.add(p.get("id"))
        for k in ("lat", "lon"):
            if not isinstance(p.get(k), (int, float)) or isinstance(p.get(k), bool):
                problems.append(f"{where}: {k} must be a number")
        if isinstance(p.get("lat"), (int, float)) and not LAT[0] <= p["lat"] <= LAT[1]:
            problems.append(f"{where}: lat {p['lat']} is not in Poland")
        if isinstance(p.get("lon"), (int, float)) and not LON[0] <= p["lon"] <= LON[1]:
            problems.append(f"{where}: lon {p['lon']} is not in Poland")
        for k in STR_OR_NULL:
            if k not in p:
                problems.append(f"{where}: missing {k}")
            elif p[k] is not None and not isinstance(p[k], str):
                problems.append(f"{where}: {k} must be a string or null")
        for k in BOOL_OR_NULL:
            if k not in p:
                problems.append(f"{where}: missing {k}")
            elif p[k] is not None and not isinstance(p[k], bool):
                problems.append(f"{where}: {k} must be true, false or null")
        if p.get("areaM2") is not None and (not isinstance(p["areaM2"], int) or isinstance(p["areaM2"], bool) or p["areaM2"] <= 0):
            problems.append(f"{where}: areaM2 must be a positive integer or null")
        if not isinstance(p.get("customersOnly"), bool):
            problems.append(f"{where}: customersOnly must be true or false")
        if p.get("source") not in {"osm", "manual"}:
            problems.append(f"{where}: source must be osm or manual")
        if p.get("website") and not p["website"].startswith(("https://", "http://")):
            problems.append(f"{where}: website must be an http(s) URL")
    return problems
