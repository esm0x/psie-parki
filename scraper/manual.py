"""Hand-written corrections from manual/poprawki.json, applied on top of OSM.

Three kinds, all keyed by the OSM id as osm.org shows it in the address bar
("node/5655915525", "way/808455741"):

  zmiany  — fields to overwrite on a park, e.g. {"fenced": true, "note": "…"}
  ukryte  — parks to leave out, with the reason as the value (closed, not a dog park)
  dodane  — parks missing from OSM, written out in full

A correction that is a fact about the place (it has a fence) belongs in OSM itself,
where every map gets it; this file is for what OSM cannot hold (adoption days at the
park) and for the week between a report and the next OSM refresh. A bad entry never
breaks the run: it is skipped and listed in `failures`.
"""

from __future__ import annotations

import re
from typing import Any

# Fields a correction may set. id, lat/lon of an OSM park and source stay OSM's.
EDITABLE = {
    "name", "locality", "district", "region", "areaM2", "fenced", "lit", "water",
    "agility", "smallDogArea", "benches", "bins", "surface", "openingHours", "operator",
    "website", "description", "note", "customersOnly",
}

TEMPLATE = {
    "name": None, "locality": None, "district": None, "region": None, "areaM2": None,
    "fenced": None, "lit": None, "water": None, "agility": None, "smallDogArea": None,
    "benches": None, "bins": None, "surface": None, "openingHours": None,
    "operator": None, "website": None, "description": None, "note": None,
    "customersOnly": False,
}

MANUAL_ID = re.compile(r"^manual/[a-z0-9-]+$")


def apply_manual(parks: list[dict[str, Any]], manual: dict[str, Any]) -> tuple[list[dict[str, Any]], list[dict[str, str]]]:
    failures: list[dict[str, str]] = []
    by_id = {p["id"]: p for p in parks}

    for pid, changes in (manual.get("zmiany") or {}).items():
        park = by_id.get(pid)
        if park is None:
            # Usually the park was redrawn in OSM and got a new id; say so, so the entry
            # gets moved instead of silently doing nothing for months.
            failures.append({"source": "manual/poprawki.json", "error": f"zmiany: {pid} is not in OSM any more"})
            continue
        unknown = set(changes) - EDITABLE
        if unknown:
            failures.append({"source": "manual/poprawki.json", "error": f"zmiany: {pid}: unknown fields {sorted(unknown)}"})
        for key in set(changes) & EDITABLE:
            park[key] = changes[key]

    hidden = manual.get("ukryte") or {}
    for pid in hidden:
        if pid not in by_id:
            failures.append({"source": "manual/poprawki.json", "error": f"ukryte: {pid} is not in OSM any more"})
    result = [p for p in parks if p["id"] not in hidden]

    seen = {p["id"] for p in result}
    for entry in manual.get("dodane") or []:
        pid = entry.get("id", "")
        try:
            if not MANUAL_ID.match(pid):
                raise ValueError("id must look like manual/<slug>")
            if pid in seen:
                raise ValueError("duplicate id")
            lat, lon = float(entry["lat"]), float(entry["lon"])
            park = dict(TEMPLATE)
            park.update({k: v for k, v in entry.items() if k in EDITABLE})
            park.update({"id": pid, "lat": round(lat, 6), "lon": round(lon, 6), "source": "manual"})
            result.append(park)
            seen.add(pid)
        except (KeyError, TypeError, ValueError) as e:
            failures.append({"source": "manual/poprawki.json", "error": f"dodane: {pid or '?'}: {e}"})

    return result, failures
