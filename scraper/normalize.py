"""From raw OSM elements to the parks the app shows.

Everything here is a pure function of the Overpass answer, so it is tested on fixtures
without a network (scraper/tests). The tagging of dog parks in OSM is loose: the same
fact turns up under several keys depending on who mapped it, and most parks carry
nothing beyond `leisure=dog_park`. So every feature is three-valued: True, False, or
None for "nobody mapped it", and the app says "brak danych" rather than "nie".
"""

from __future__ import annotations

import math
import re
from typing import Any, Optional

YES = {"yes", "true", "1", "designated", "separate"}
NO = {"no", "false", "0", "none"}

# OSM surface values → what a dog owner would call them. Unknown values are dropped
# rather than shown raw: "compacted" means nothing on a Polish screen.
SURFACES = {
    "grass": "trawa",
    "sand": "piasek",
    "gravel": "żwir",
    "fine_gravel": "drobny żwir",
    "pebblestone": "żwir",
    "dirt": "ziemia",
    "earth": "ziemia",
    "ground": "ziemia",
    "mud": "ziemia",
    "woodchips": "zrębki",
    "wood": "zrębki",
    "bark": "kora",
    "compacted": "utwardzona",
    "asphalt": "asfalt",
    "paving_stones": "kostka",
    "concrete": "beton",
    "artificial_turf": "sztuczna trawa",
    "rubber": "nawierzchnia gumowa",
    "tartan": "nawierzchnia gumowa",
    "unpaved": "nieutwardzona",
    "paved": "utwardzona",
}

# Parks nobody else may enter: a hotel's or a breeder's run is no help to anyone
# looking for a place to walk a dog.
CLOSED_ACCESS = {"private", "no"}

# Names that only say what the thing is. Shown as a name they would make every second
# row "Wybieg dla psów"; dropped, the app titles an unnamed park the same way anyway.
GENERIC_NAME = re.compile(
    r"^(psi\s+)?(wybieg|park|plac|wybieg\s+(dla|do)\s+ps(ów|a|y))(\s+dla\s+ps(ów|a))?$|^dog\s*park$",
    re.IGNORECASE,
)

# Prefixes the Polish admin boundaries carry in their names. "gmina Lubomia" is the
# rural gmina; the village a park is in is "Lubomia".
ADMIN_PREFIX = re.compile(r"^(gmina|miasto|osiedle|dzielnica|sołectwo)\s+", re.IGNORECASE)


def tri(tags: dict[str, str], *keys: str) -> Optional[bool]:
    """First of `keys` that says yes or no; None when none of them is mapped."""
    for key in keys:
        raw = (tags.get(key) or "").strip().lower()
        if not raw:
            continue
        first = raw.split(";")[0].strip()
        if first in YES:
            return True
        if first in NO:
            return False
        # lit=24/7, lit=sunset-sunrise, drinking_water=seasonal: present, with detail.
        if key == "lit" or key.endswith("drinking_water"):
            return True
    return None


def fenced(tags: dict[str, str]) -> Optional[bool]:
    value = tri(tags, "fenced", "fence", "dog_park:fenced")
    if value is not None:
        return value
    barrier = (tags.get("barrier") or "").lower()
    if barrier in {"fence", "wall", "chain_link", "wire_fence"}:
        return True
    return None


def surface(tags: dict[str, str]) -> Optional[str]:
    raw = (tags.get("surface") or "").lower()
    names = [SURFACES[v.strip()] for v in raw.split(";") if v.strip() in SURFACES]
    unique = list(dict.fromkeys(names))
    return ", ".join(unique) if unique else None


def opening_hours(tags: dict[str, str]) -> Optional[str]:
    raw = (tags.get("opening_hours") or "").strip()
    if not raw:
        return None
    if raw == "24/7":
        return "całą dobę"
    return raw


def clean(value: Optional[str], limit: int = 300) -> Optional[str]:
    if value is None:
        return None
    text = re.sub(r"\s+", " ", value).strip()
    if not text:
        return None
    return text if len(text) <= limit else text[: limit - 1].rstrip() + "…"


def name_of(tags: dict[str, str]) -> Optional[str]:
    name = clean(tags.get("name"), 120)
    if name and GENERIC_NAME.match(name.strip(" .")):
        return None
    return name


def website(tags: dict[str, str]) -> Optional[str]:
    for key in ("website", "contact:website", "url"):
        url = (tags.get(key) or "").strip().split(";")[0].strip()
        if url.startswith(("http://", "https://")):
            return url
        if url and "." in url and " " not in url:
            return "https://" + url
    return None


# --------------------------------------------------------------------------- #
# geometry
# --------------------------------------------------------------------------- #

EARTH_R = 6_371_008.8


def ring_area(points: list[tuple[float, float]]) -> float:
    """Area in m² of a closed lat/lon ring, on a local equirectangular projection.

    Good to a percent or so at the size of a dog park, which is all the screen needs
    ("ok. 1 200 m²"); a proper geodesic area would be precision nobody can see.
    """
    if len(points) < 4 or points[0] != points[-1]:
        return 0.0
    lat0 = math.radians(sum(p[0] for p in points) / len(points))
    xs = [math.radians(p[1]) * EARTH_R * math.cos(lat0) for p in points]
    ys = [math.radians(p[0]) * EARTH_R for p in points]
    s = sum(xs[i] * ys[i + 1] - xs[i + 1] * ys[i] for i in range(len(points) - 1))
    return abs(s) / 2


def geometry_of(el: dict[str, Any]) -> tuple[Optional[float], Optional[float], Optional[int]]:
    """(lat, lon, area m²) of a node, way or relation from `out geom`."""
    if el["type"] == "node":
        return el.get("lat"), el.get("lon"), None

    rings: list[list[tuple[float, float]]] = []
    if el["type"] == "way":
        rings.append([(p["lat"], p["lon"]) for p in el.get("geometry") or [] if p])
    else:
        # Multipolygon: outer rings count, inner ones are holes. Members are often split
        # into several ways; only the ones that close on their own are measured, so a
        # relation stitched from fragments gets no area rather than a wrong one.
        for m in el.get("members") or []:
            pts = [(p["lat"], p["lon"]) for p in m.get("geometry") or [] if p]
            if m.get("role") == "outer" and pts:
                rings.append(pts)

    pts = [p for r in rings for p in r]
    if not pts:
        b = el.get("bounds") or el.get("center")
        if b and "minlat" in b:
            return (b["minlat"] + b["maxlat"]) / 2, (b["minlon"] + b["maxlon"]) / 2, None
        if b and "lat" in b:
            return b["lat"], b["lon"], None
        return None, None, None

    # Centre of the bounding box: always inside a convex park, and close enough for
    # the irregular ones to put the pin on the right street.
    lats = [p[0] for p in pts]
    lons = [p[1] for p in pts]
    lat, lon = (min(lats) + max(lats)) / 2, (min(lons) + max(lons)) / 2
    area = sum(ring_area(r) for r in rings)
    return lat, lon, (round(area) if area >= 1 else None)


# --------------------------------------------------------------------------- #
# admin areas
# --------------------------------------------------------------------------- #

def strip_admin(name: Optional[str]) -> Optional[str]:
    if not name or name.startswith("<"):   # "< multiple values found >"
        return None
    return ADMIN_PREFIX.sub("", name).strip() or None


def place_of(adm: dict[str, str]) -> tuple[Optional[str], Optional[str], Optional[str]]:
    """(locality, district, voivodeship) from the admin areas a park lies in.

    Poland in OSM: 4 = województwo, 6 = powiat, 7 = gmina, 8 = miasto or sołectwo,
    9 and 10 = dzielnica, osiedle. A city gmina is named after its city ("Rybnik");
    a rural one is "gmina Lubomia" and its villages are level 8 where mapped.
    """
    gmina = strip_admin(adm.get("gmina"))
    town = strip_admin(adm.get("miejscowosc"))
    # Level 9 is the district people name (Mokotów, Boguszowice); level 10 is the
    # smaller osiedle, used only where a city maps nothing at level 9.
    district = strip_admin(adm.get("dzielnica")) or strip_admin(adm.get("osiedle"))
    region = strip_admin(adm.get("wojewodztwo"))
    if region:
        region = re.sub(r"^województwo\s+", "", region, flags=re.IGNORECASE)
    locality = town or gmina
    if district and district == locality:
        district = None
    return locality, district, region


# --------------------------------------------------------------------------- #
# one park
# --------------------------------------------------------------------------- #

def park_from(el: dict[str, Any], adm: Optional[dict[str, str]] = None) -> Optional[dict[str, Any]]:
    """The app's park for one OSM element, or None when it is not one people can use."""
    tags = el.get("tags") or {}
    if tags.get("leisure") != "dog_park":
        return None
    if (tags.get("access") or "").lower() in CLOSED_ACCESS:
        return None

    lat, lon, area = geometry_of(el)
    if lat is None or lon is None:
        return None

    locality, district, region = place_of(adm or {})
    locality = locality or clean(tags.get("addr:city")) or clean(tags.get("addr:place"))

    return {
        "id": f"{el['type']}/{el['id']}",
        "name": name_of(tags),
        "lat": round(lat, 6),
        "lon": round(lon, 6),
        "locality": locality,
        "district": district,
        "region": region,
        "areaM2": area,
        "fenced": fenced(tags),
        "lit": tri(tags, "lit"),
        "water": tri(tags, "drinking_water", "dog_park:drinking_water", "water_point"),
        "agility": tri(tags, "dog_park:agility", "agility", "dog_agility", "playground:dog"),
        "smallDogArea": tri(tags, "dog_park:small_dogs", "small_dog", "dog_park:separate_small_dogs"),
        "benches": tri(tags, "bench", "benches"),
        "bins": tri(tags, "waste_basket", "dog_park:waste_basket", "vending:excrement_bags"),
        "surface": surface(tags),
        "openingHours": opening_hours(tags),
        "operator": clean(tags.get("operator"), 120),
        "website": website(tags),
        "description": clean(tags.get("description:pl") or tags.get("description")),
        "note": None,
        "customersOnly": (tags.get("access") or "").lower() == "customers",
        "source": "osm",
    }
