"""What submissions from the app do to the map: pure rules, no network.

The worker (worker/) already threw out junk text and over-eager senders, and turned
each accepted submission into a GitHub issue with an anonymised record: `autor` is a
hash of the install, `siec` a hash of the network it came from. Here those records
meet the map, and nothing is taken on one person's word alone where it could do harm:

  nowy          a new park goes on the map at once, marked "niepotwierdzony", unless
                there is already a park within 60 m (then it counts as a confirmation)
                or the sender already has three unconfirmed parks waiting.
                One independent confirmation removes the mark.
  potwierdzam   confirms a park; for an unconfirmed one, the confirmation must come
                from someone else (another install *and* another network).
  nie_istnieje  hides an unconfirmed park after one independent report (it rested on
                one person's word too), a confirmed or OSM park after two that
                outnumber its confirmations.
  cechy         a vote per person and feature. Fills in what OSM does not say from the
                first vote; overrides what OSM does say only when two people agree.

The state lives in manual/zgloszenia.json, which only the moderation workflow writes,
and every decision is also a comment on the issue, so it can be read and reverted.
"""

from __future__ import annotations

import math
from dataclasses import dataclass
from datetime import date
from typing import Any, Optional

FEATURES = ("fenced", "lit", "water", "agility", "smallDogArea")
TYPES = ("nowy", "cechy", "potwierdzam", "nie_istnieje")

NEAR_M = 60                 # a "new" park this close to a known one is that park
MAX_PENDING_PER_AUTHOR = 3  # unconfirmed parks one install may have waiting
HIDE_OSM_AFTER = 2          # independent "nie istnieje" reports to hide a known park

EMPTY_STATE = {
    "_opis": [
        "Stan zgłoszeń z aplikacji. Plik zapisuje wyłącznie workflow moderacji",
        "(.github/workflows/moderacja.yml) — nie edytuj go ręcznie. Ręczne poprawki",
        "idą do manual/poprawki.json, który ma pierwszeństwo przed tym plikiem.",
        "autor i siec to skróty (HMAC) instalacji i sieci, nie dane osobowe.",
    ],
    "przetworzone": [],
    "nowe": {},
    "glosy": {},
    "potwierdzenia": {},
    "zaprzeczenia": {},
}


@dataclass
class Outcome:
    """What happened to one submission; becomes labels and a comment on its issue."""
    status: str            # "przyjete" | "niepotwierdzone" | "odrzucone"
    message: str           # Polish, for the issue (and through the worker, the app)
    reason: Optional[str] = None   # short slug for the "powod:" label when rejected
    park_id: Optional[str] = None


def distance_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    r = 6_371_008.8
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lon2 - lon1)
    h = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(min(1.0, h)))


def witness(record: dict[str, Any], day: str) -> dict[str, str]:
    return {"autor": record["autor"], "siec": record["siec"], "data": day}


def independent(a: dict[str, str], b: dict[str, str]) -> bool:
    """Two reports count as two people only from different installs on different networks."""
    return a["autor"] != b["autor"] and a["siec"] != b["siec"]


def independent_count(witnesses: list[dict[str, str]]) -> int:
    """Size of a set of reports where every pair is independent (greedy, in order)."""
    kept: list[dict[str, str]] = []
    for w in witnesses:
        if all(independent(w, k) for k in kept):
            kept.append(w)
    return len(kept)


def check_record(rec: Any) -> Optional[str]:
    """Second line of defence: the worker validates, but an issue body can be edited."""
    if not isinstance(rec, dict) or rec.get("v") != 1 or rec.get("typ") not in TYPES:
        return "nie jest zgłoszeniem z aplikacji"
    for k in ("autor", "siec"):
        if not isinstance(rec.get(k), str) or not rec[k]:
            return f"brak pola {k}"
    if rec["typ"] == "nowy":
        lat, lon = rec.get("lat"), rec.get("lon")
        if not all(isinstance(x, (int, float)) and not isinstance(x, bool) for x in (lat, lon)):
            return "brak położenia"
        if not (48.9 <= lat <= 55.0 and 14.0 <= lon <= 24.2):
            return "punkt poza Polską"
    elif not isinstance(rec.get("parkId"), str):
        return "brak id wybiegu"
    cechy = rec.get("cechy") or {}
    if not isinstance(cechy, dict) or any(k not in FEATURES or not isinstance(v, bool) for k, v in cechy.items()):
        return "nieprawidłowe cechy"
    name = rec.get("nazwa")
    if name is not None and (not isinstance(name, str) or len(name) > 60):
        return "nieprawidłowa nazwa"
    return None


class Moderator:
    """Applies submissions, in issue order, to the state and the list of known parks."""

    def __init__(self, state: dict[str, Any], known: list[dict[str, Any]], today: Optional[date] = None):
        self.state = {**EMPTY_STATE, **state}
        for key in ("nowe", "glosy", "potwierdzenia", "zaprzeczenia"):
            self.state[key] = dict(self.state.get(key) or {})
        self.state["przetworzone"] = list(self.state.get("przetworzone") or [])
        self.known = {p["id"]: p for p in known}
        self.day = (today or date.today()).isoformat()

    # ------------------------------------------------------------------ helpers

    def _pending(self) -> dict[str, dict[str, Any]]:
        return {k: v for k, v in self.state["nowe"].items() if v["status"] != "ukryty"}

    def _nearest(self, lat: float, lon: float) -> Optional[tuple[str, float]]:
        best = None
        candidates = [(pid, p["lat"], p["lon"]) for pid, p in self.known.items()]
        candidates += [(pid, p["lat"], p["lon"]) for pid, p in self._pending().items()]
        for pid, plat, plon in candidates:
            d = distance_m(lat, lon, plat, plon)
            if best is None or d < best[1]:
                best = (pid, d)
        return best

    def _exists(self, pid: str) -> bool:
        return pid in self.known or pid in self._pending()

    def _vote(self, pid: str, record: dict[str, Any]) -> None:
        votes = self.state["glosy"].setdefault(pid, {})
        for feature, value in (record.get("cechy") or {}).items():
            # One vote per install and feature: a changed mind replaces the old vote.
            votes.setdefault(feature, {})[record["autor"] + "/" + record["siec"]] = value

    # ------------------------------------------------------------------ rules

    def apply(self, nr: int, record: dict[str, Any], context: Optional[dict[str, Any]] = None) -> Outcome:
        if nr in self.state["przetworzone"]:
            return Outcome("przyjete", "To zgłoszenie było już przetworzone.")
        problem = check_record(record)
        if problem:
            outcome = Outcome("odrzucone", f"Nie przyjęte: {problem}.", "format")
        else:
            outcome = getattr(self, "_" + record["typ"])(nr, record, context or {})
        self.state["przetworzone"].append(nr)
        return outcome

    def _nowy(self, nr: int, rec: dict[str, Any], context: dict[str, Any]) -> Outcome:
        near = self._nearest(rec["lat"], rec["lon"])
        if near and near[1] <= NEAR_M:
            pid = near[0]
            self._vote(pid, rec)
            confirmed = self._potwierdzam(nr, {**rec, "parkId": pid}, context)
            return Outcome(
                confirmed.status if confirmed.status != "odrzucone" else "przyjete",
                f"Ten wybieg już jest na mapie ({round(near[1])} m dalej). " + confirmed.message,
                park_id=pid,
            )
        if context.get("blocked"):
            return Outcome("odrzucone", f"W tym miejscu nie może być wybiegu: {context['blocked']}. "
                           "Przesuń pinezkę dokładnie na wybieg.", "miejsce")
        mine = [p for p in self._pending().values() if p["status"] == "niepotwierdzony" and p["autor"] == rec["autor"]]
        if len(mine) >= MAX_PENDING_PER_AUTHOR:
            return Outcome("odrzucone", "Masz już trzy wybiegi czekające na potwierdzenie. "
                           "Dodasz kolejny, gdy ktoś potwierdzi któryś z nich.", "limit")

        locality, district, region = context.get("locality"), context.get("district"), context.get("region")
        if not locality and near and near[1] <= 5000:
            # No admin areas from Overpass: borrow the place of the nearest known park,
            # which is right far more often than "unknown" is useful.
            neighbour = self.known.get(near[0]) or self._pending().get(near[0]) or {}
            locality = neighbour.get("locality")
            region = region or neighbour.get("region")

        pid = f"manual/z-{nr}"
        self.state["nowe"][pid] = {
            "lat": rec["lat"], "lon": rec["lon"], "nazwa": rec.get("nazwa"),
            "locality": locality, "district": district, "region": region,
            "autor": rec["autor"], "siec": rec["siec"], "zgloszono": self.day,
            "potwierdzenia": [], "zaprzeczenia": [], "status": "niepotwierdzony",
        }
        self._vote(pid, rec)
        return Outcome("niepotwierdzone", "Dziękujemy! Wybieg jest już w aplikacji z dopiskiem "
                       "„niepotwierdzony”. Zniknie on, gdy potwierdzi go ktoś jeszcze.", park_id=pid)

    def _potwierdzam(self, nr: int, rec: dict[str, Any], context: dict[str, Any]) -> Outcome:
        pid = rec["parkId"]
        if not self._exists(pid):
            return Outcome("odrzucone", "Tego wybiegu nie ma już na mapie.", "nieznany", pid)
        w = witness(rec, self.day)
        pending = self.state["nowe"].get(pid)
        if pending is not None:
            creator = {"autor": pending["autor"], "siec": pending["siec"]}
            if not independent(w, creator) or not all(independent(w, x) for x in pending["potwierdzenia"]):
                return Outcome("przyjete", "Dziękujemy! Potwierdzenie musi jednak przyjść od innej osoby "
                               "niż ta, która dodała wybieg.", park_id=pid)
            pending["potwierdzenia"].append(w)
            pending["status"] = "potwierdzony"
            return Outcome("przyjete", "Dziękujemy! Wybieg jest teraz potwierdzony.", park_id=pid)
        self.state["potwierdzenia"].setdefault(pid, []).append(w)
        return Outcome("przyjete", "Dziękujemy za potwierdzenie!", park_id=pid)

    def _nie_istnieje(self, nr: int, rec: dict[str, Any], context: dict[str, Any]) -> Outcome:
        pid = rec["parkId"]
        if not self._exists(pid):
            return Outcome("przyjete", "Tego wybiegu już nie ma na mapie. Dziękujemy!", park_id=pid)
        w = witness(rec, self.day)
        pending = self.state["nowe"].get(pid)
        if pending is not None:
            creator = {"autor": pending["autor"], "siec": pending["siec"]}
            if w["autor"] == creator["autor"]:
                pending["status"] = "ukryty"
                return Outcome("przyjete", "Usunęliśmy wybieg, który wcześniej dodałeś. Dziękujemy!", park_id=pid)
            pending["zaprzeczenia"].append(w)
            support = 1 + independent_count(pending["potwierdzenia"])
            against = independent_count(pending["zaprzeczenia"])
            if pending["status"] == "niepotwierdzony" or against > support:
                pending["status"] = "ukryty"
                return Outcome("przyjete", "Dziękujemy! Ukryliśmy ten wybieg.", park_id=pid)
            return Outcome("przyjete", "Dziękujemy! Gdy ktoś jeszcze to zgłosi, ukryjemy wybieg.", park_id=pid)

        denials = self.state["zaprzeczenia"].setdefault(pid, [])
        if any(not independent(w, d) for d in denials):
            return Outcome("przyjete", "Dziękujemy! To zgłoszenie już mamy.", park_id=pid)
        denials.append(w)
        if self.hidden_known(pid):
            return Outcome("przyjete", "Dziękujemy! Ukryliśmy ten wybieg.", park_id=pid)
        return Outcome("przyjete", "Dziękujemy! Gdy ktoś jeszcze to zgłosi, ukryjemy wybieg.", park_id=pid)

    def _cechy(self, nr: int, rec: dict[str, Any], context: dict[str, Any]) -> Outcome:
        pid = rec["parkId"]
        if not self._exists(pid):
            return Outcome("odrzucone", "Tego wybiegu nie ma już na mapie.", "nieznany", pid)
        self._vote(pid, rec)
        return Outcome("przyjete", "Dziękujemy! Uzupełnimy opis wybiegu.", park_id=pid)

    # ------------------------------------------------------------------ result

    def hidden_known(self, pid: str) -> bool:
        against = independent_count(self.state["zaprzeczenia"].get(pid, []))
        support = independent_count(self.state["potwierdzenia"].get(pid, []))
        return against >= HIDE_OSM_AFTER and against > support


def tally(osm_value: Optional[bool], votes: dict[str, bool]) -> Optional[bool]:
    """The value to show for one feature of one park, given OSM's and the votes."""
    yes = sum(1 for v in votes.values() if v)
    no = len(votes) - yes
    if osm_value is None:
        if yes == no:
            return None
        return yes > no
    against = no if osm_value else yes
    agree = len(votes) - against
    if against >= 2 and against > agree:
        return not osm_value
    return osm_value


def community_layer(parks: list[dict[str, Any]], state: dict[str, Any]) -> list[dict[str, Any]]:
    """Known parks with the community's changes, plus its new parks."""
    mod = Moderator(state, parks)
    out = []
    for park in parks:
        if mod.hidden_known(park["id"]):
            continue
        park = dict(park)
        for feature, votes in (state.get("glosy") or {}).get(park["id"], {}).items():
            if feature in FEATURES:
                park[feature] = tally(park.get(feature), votes)
        out.append(park)

    for pid, p in (state.get("nowe") or {}).items():
        if p["status"] == "ukryty":
            continue
        park = {
            "id": pid, "name": p.get("nazwa"), "lat": p["lat"], "lon": p["lon"],
            "locality": p.get("locality"), "district": p.get("district"), "region": p.get("region"),
            "areaM2": None, "surface": None, "openingHours": None, "operator": None, "website": None,
            "description": None, "note": None, "benches": None, "bins": None,
            "customersOnly": False, "source": "zgloszenie",
            "confirmed": p["status"] == "potwierdzony",
        }
        for feature in FEATURES:
            park[feature] = tally(None, (state.get("glosy") or {}).get(pid, {}).get(feature, {}))
        out.append(park)
    return out
