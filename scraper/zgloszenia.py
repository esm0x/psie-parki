"""What submissions from the app do to the map: pure rules, no network.

The worker (worker/) already threw out junk names and over-eager senders and turned
each accepted submission into a GitHub issue with an anonymised record: `autor` is a
hash of the install id, `siec` a hash of the network it came from.

The install id is chosen by the app, so a script can invent a new one per request:
`autor` tells one honest install's reports apart and nothing more. Everything that
needs "two different people" counts networks (`siec`): a home Wi-Fi, a mobile
operator's /64. Two installs, or one person on Wi-Fi and then LTE, is still easy;
which is why nothing here is permanent on the word of a few networks, the daily caps
are small, and a person can always undo it in manual/poprawki.json.

  nowy          a new park goes on the map marked "niepotwierdzony", unless there is a
                park (or a park someone said is not there) within 60 m, its network
                already has 3 waiting or added 2 today, or 20 went up today in total.
                Confirmed by one report from another network on a later day.
                Unconfirmed parks expire after 90 days.
  potwierdzam   confirms a park; for an app park, only from another network, a day on.
  nie_istnieje  hides an unconfirmed park after one report from another network (it
                rested on one network's word too); its sender can take it back too.
                Hides an OSM park only after 3 networks on at least 2 days outnumber
                its confirmations, and at most 3 OSM parks a day across the country.
  cechy         one vote per network and feature. Fills in what OSM does not say from
                the first vote; overrides what OSM says only when two networks agree.

The state lives in manual/zgloszenia.json, which only the moderation workflow writes;
every decision is also a comment on the issue, so it can be read and reverted.
"""

from __future__ import annotations

import math
from dataclasses import asdict, dataclass
from datetime import date, timedelta
from typing import Any, Optional

FEATURES = ("fenced", "lit", "water", "agility", "smallDogArea")
TYPES = ("nowy", "cechy", "potwierdzam", "nie_istnieje")

NEAR_M = 60                   # a "new" park this close to a known one is that park
MAX_PENDING_PER_NETWORK = 3   # unconfirmed parks one network may have waiting
MAX_NEW_PER_NETWORK_DAY = 2
MAX_NEW_PER_DAY = 20          # across the country: a flood stays a trickle
EXPIRE_DAYS = 90              # unconfirmed parks nobody vouched for go away
HIDE_OSM_NETWORKS = 3         # networks saying "not there" before a mapped park hides
HIDE_OSM_DAYS = 2             # ...on at least this many different days
MAX_OSM_HIDES_PER_DAY = 3

EMPTY_STATE = {
    "_opis": [
        "Stan zgłoszeń z aplikacji. Plik zapisuje wyłącznie workflow moderacji",
        "(.github/workflows/moderacja.yml) — nie edytuj go ręcznie. Ręczne poprawki",
        "idą do manual/poprawki.json, który ma pierwszeństwo przed tym plikiem.",
        "autor i siec to skróty (HMAC) instalacji i sieci, nie dane osobowe.",
    ],
    "przetworzone": [],
    "wyniki": {},
    "nowe": {},
    "glosy": {},
    "potwierdzenia": {},
    "zaprzeczenia": {},
    "ukryte_osm": {},
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


def networks(witnesses: list[dict[str, str]]) -> set[str]:
    return {w["siec"] for w in witnesses}


def is_number(x: Any) -> bool:
    return isinstance(x, (int, float)) and not isinstance(x, bool) and math.isfinite(x)


def check_record(rec: Any) -> Optional[str]:
    """Second line of defence: the worker validates, but an issue body can be edited."""
    if not isinstance(rec, dict) or rec.get("v") != 1 or rec.get("typ") not in TYPES:
        return "nie jest zgłoszeniem z aplikacji"
    for k in ("autor", "siec"):
        if not isinstance(rec.get(k), str) or not rec[k] or len(rec[k]) > 64:
            return f"brak pola {k}"
    if rec["typ"] == "nowy":
        lat, lon = rec.get("lat"), rec.get("lon")
        if not (is_number(lat) and is_number(lon)):
            return "brak położenia"
        if not (48.9 <= lat <= 55.0 and 14.0 <= lon <= 24.2):
            return "punkt poza Polską"
    elif not isinstance(rec.get("parkId"), str) or len(rec["parkId"]) > 80:
        return "brak id wybiegu"
    cechy = rec.get("cechy") or {}
    if not isinstance(cechy, dict) or any(k not in FEATURES or not isinstance(v, bool) for k, v in cechy.items()):
        return "nieprawidłowe cechy"
    name = rec.get("nazwa")
    if name is not None and (not isinstance(name, str) or len(name) > 60 or any(c in name for c in "`{}<>@#")):
        return "nieprawidłowa nazwa"
    return None


class Moderator:
    """Applies submissions, in issue order, to the state and the list of known parks."""

    def __init__(self, state: dict[str, Any], known: list[dict[str, Any]], today: Optional[date] = None):
        self.state: dict[str, Any] = {**EMPTY_STATE, **(state or {})}
        for key in ("wyniki", "nowe", "glosy", "potwierdzenia", "zaprzeczenia", "ukryte_osm"):
            self.state[key] = dict(self.state.get(key) or {})
        self.state["przetworzone"] = list(self.state.get("przetworzone") or [])
        self.known = {p["id"]: p for p in known}
        self.today = today or date.today()
        self.day = self.today.isoformat()

    # ------------------------------------------------------------------ helpers

    def _pending(self, include_hidden: bool = False) -> dict[str, dict[str, Any]]:
        return {k: v for k, v in self.state["nowe"].items()
                if include_hidden or v["status"] in ("niepotwierdzony", "potwierdzony")}

    def _nearest(self, lat: float, lon: float, places: dict[str, tuple[float, float]]) -> Optional[tuple[str, float]]:
        best = None
        for pid, (plat, plon) in places.items():
            d = distance_m(lat, lon, plat, plon)
            if best is None or d < best[1]:
                best = (pid, d)
        return best

    def _visible_places(self) -> dict[str, tuple[float, float]]:
        out = {pid: (p["lat"], p["lon"]) for pid, p in self.known.items() if pid not in self.state["ukryte_osm"]}
        out.update({pid: (p["lat"], p["lon"]) for pid, p in self._pending().items()})
        return out

    def _exists(self, pid: str) -> bool:
        return (pid in self.known and pid not in self.state["ukryte_osm"]) or pid in self._pending()

    def _vote(self, pid: str, record: dict[str, Any]) -> None:
        votes = self.state["glosy"].setdefault(pid, {})
        for feature, value in (record.get("cechy") or {}).items():
            # One vote per network and feature: a changed mind replaces the old vote,
            # and a second install on the same Wi-Fi does not add one.
            votes.setdefault(feature, {})[record["siec"]] = value

    def _witness(self, rec: dict[str, Any]) -> dict[str, str]:
        return {"autor": rec["autor"], "siec": rec["siec"], "data": self.day}

    # ------------------------------------------------------------------ rules

    def apply(self, nr: int, record: dict[str, Any], context: Optional[dict[str, Any]] = None) -> Outcome:
        key = str(nr)
        if nr in self.state["przetworzone"]:
            # Re-run after a failed announcement: say again what was said the first time.
            saved = self.state["wyniki"].get(key)
            return Outcome(**saved) if saved else Outcome("przyjete", "To zgłoszenie było już przetworzone.")
        problem = check_record(record)
        if problem:
            outcome = Outcome("odrzucone", f"Nie przyjęte: {problem}.", "format")
        else:
            outcome = getattr(self, "_" + record["typ"])(nr, record, context or {})
        self.state["przetworzone"].append(nr)
        self.state["wyniki"][key] = asdict(outcome)
        return outcome

    def _nowy(self, nr: int, rec: dict[str, Any], context: dict[str, Any]) -> Outcome:
        lat, lon = rec["lat"], rec["lon"]
        near = self._nearest(lat, lon, self._visible_places())
        if near and near[1] <= NEAR_M:
            pid = near[0]
            self._vote(pid, rec)
            confirmed = self._potwierdzam(nr, {**rec, "parkId": pid}, context)
            return Outcome(
                confirmed.status if confirmed.status != "odrzucone" else "przyjete",
                f"Ten wybieg już jest na mapie ({round(near[1])} m dalej). " + confirmed.message,
                park_id=pid,
            )
        gone = {pid: (p["lat"], p["lon"]) for pid, p in self.state["nowe"].items() if p["status"] == "ukryty"}
        gone.update({pid: (self.known[pid]["lat"], self.known[pid]["lon"]) for pid in self.state["ukryte_osm"] if pid in self.known})
        near_gone = self._nearest(lat, lon, gone)
        if near_gone and near_gone[1] <= NEAR_M:
            return Outcome("odrzucone", "W tym miejscu kilka osób zgłosiło, że wybiegu nie ma.", "ukryty")
        if context.get("blocked"):
            return Outcome("odrzucone", f"W tym miejscu nie może być wybiegu: {context['blocked']}. "
                           "Przesuń pinezkę dokładnie na wybieg.", "miejsce")

        waiting = [p for p in self._pending().values() if p["status"] == "niepotwierdzony"]
        if sum(1 for p in waiting if p["siec"] == rec["siec"]) >= MAX_PENDING_PER_NETWORK:
            return Outcome("odrzucone", "Z tej sieci czekają już trzy wybiegi na potwierdzenie. "
                           "Dodasz kolejny, gdy ktoś potwierdzi któryś z nich.", "limit")
        today = [p for p in self.state["nowe"].values() if p["zgloszono"] == self.day]
        if sum(1 for p in today if p["siec"] == rec["siec"]) >= MAX_NEW_PER_NETWORK_DAY or len(today) >= MAX_NEW_PER_DAY:
            return Outcome("odrzucone", "Na dziś mamy już dość nowych wybiegów do sprawdzenia. Spróbuj jutro — dziękujemy!", "limit")

        locality, district, region = context.get("locality"), context.get("district"), context.get("region")
        if not locality and near and near[1] <= 5000:
            # No admin areas from Overpass: borrow the place of the nearest known park,
            # which is right far more often than "unknown" is useful.
            neighbour = self.known.get(near[0]) or self.state["nowe"].get(near[0]) or {}
            locality = neighbour.get("locality")
            region = region or neighbour.get("region")

        pid = f"manual/z-{nr}"
        self.state["nowe"][pid] = {
            "lat": lat, "lon": lon, "nazwa": rec.get("nazwa"),
            "locality": locality, "district": district, "region": region,
            "autor": rec["autor"], "siec": rec["siec"], "zgloszono": self.day,
            "potwierdzenia": [], "zaprzeczenia": [], "status": "niepotwierdzony",
        }
        self._vote(pid, rec)
        return Outcome("niepotwierdzone", "Dziękujemy! Wybieg jest już w aplikacji z dopiskiem "
                       "„niepotwierdzony”. Zniknie on, gdy potwierdzi go ktoś jeszcze.", park_id=pid)

    def _potwierdzam(self, nr: int, rec: dict[str, Any], context: dict[str, Any]) -> Outcome:
        pid = rec["parkId"]
        # A hidden mapped park can still be vouched for (from a copy of the list from
        # before it was hidden): enough confirmations put it back, see sweep().
        if not self._exists(pid) and pid not in self.known:
            return Outcome("odrzucone", "Tego wybiegu nie ma już na mapie.", "nieznany", pid)
        w = self._witness(rec)
        pending = self.state["nowe"].get(pid)
        if pending is not None:
            if w["siec"] == pending["siec"] or w["siec"] in networks(pending["potwierdzenia"]):
                return Outcome("przyjete", "Dziękujemy! Potwierdzenie musi jednak przyjść od innej osoby "
                               "niż ta, która dodała wybieg.", park_id=pid)
            if self.day <= pending["zgloszono"]:
                return Outcome("przyjete", "Dziękujemy! Potwierdzenia liczymy od następnego dnia po "
                               "dodaniu wybiegu — wróć jutro.", park_id=pid)
            pending["potwierdzenia"].append(w)
            pending["status"] = "potwierdzony"
            return Outcome("przyjete", "Dziękujemy! Wybieg jest teraz potwierdzony.", park_id=pid)
        confirmations = self.state["potwierdzenia"].setdefault(pid, [])
        if w["siec"] not in networks(confirmations):
            confirmations.append(w)
        return Outcome("przyjete", "Dziękujemy za potwierdzenie!", park_id=pid)

    def _nie_istnieje(self, nr: int, rec: dict[str, Any], context: dict[str, Any]) -> Outcome:
        pid = rec["parkId"]
        if not self._exists(pid):
            return Outcome("przyjete", "Tego wybiegu już nie ma na mapie. Dziękujemy!", park_id=pid)
        w = self._witness(rec)
        pending = self.state["nowe"].get(pid)
        if pending is not None:
            if w["autor"] == pending["autor"]:
                pending["status"] = "ukryty"
                return Outcome("przyjete", "Usunęliśmy wybieg, który wcześniej dodałeś. Dziękujemy!", park_id=pid)
            if w["siec"] == pending["siec"] or w["siec"] in networks(pending["zaprzeczenia"]):
                return Outcome("przyjete", "Dziękujemy! To zgłoszenie już mamy.", park_id=pid)
            pending["zaprzeczenia"].append(w)
            support = 1 + len(networks(pending["potwierdzenia"]))
            against = len(networks(pending["zaprzeczenia"]))
            if pending["status"] == "niepotwierdzony" or against > support:
                pending["status"] = "ukryty"
                return Outcome("przyjete", "Dziękujemy! Ukryliśmy ten wybieg.", park_id=pid)
            return Outcome("przyjete", "Dziękujemy! Gdy ktoś jeszcze to zgłosi, ukryjemy wybieg.", park_id=pid)

        denials = self.state["zaprzeczenia"].setdefault(pid, [])
        if w["siec"] in networks(denials):
            return Outcome("przyjete", "Dziękujemy! To zgłoszenie już mamy.", park_id=pid)
        denials.append(w)
        if self._hide_known(pid):
            return Outcome("przyjete", "Dziękujemy! Ukryliśmy ten wybieg.", park_id=pid)
        return Outcome("przyjete", "Dziękujemy! Sprawdzimy to, gdy podobnych zgłoszeń będzie więcej.", park_id=pid)

    def _cechy(self, nr: int, rec: dict[str, Any], context: dict[str, Any]) -> Outcome:
        pid = rec["parkId"]
        if not self._exists(pid):
            return Outcome("odrzucone", "Tego wybiegu nie ma na mapie.", "nieznany", pid)
        self._vote(pid, rec)
        return Outcome("przyjete", "Dziękujemy! Uzupełnimy opis wybiegu.", park_id=pid)

    # ------------------------------------------------------------------ mapped parks

    def _should_hide(self, pid: str) -> bool:
        denials = self.state["zaprzeczenia"].get(pid, [])
        against = len(networks(denials))
        support = len(networks(self.state["potwierdzenia"].get(pid, [])))
        days = len({d["data"] for d in denials})
        return against >= HIDE_OSM_NETWORKS and days >= HIDE_OSM_DAYS and against > support

    def _hide_known(self, pid: str) -> bool:
        if pid in self.state["ukryte_osm"] or not self._should_hide(pid):
            return pid in self.state["ukryte_osm"]
        hidden_today = sum(1 for d in self.state["ukryte_osm"].values() if d == self.day)
        if hidden_today >= MAX_OSM_HIDES_PER_DAY:
            return False  # sweep() tries again tomorrow
        self.state["ukryte_osm"][pid] = self.day
        return True

    def sweep(self) -> None:
        """End of a run: expire forgotten app parks, hide or restore mapped ones."""
        cutoff = (self.today - timedelta(days=EXPIRE_DAYS)).isoformat()
        for p in self.state["nowe"].values():
            if p["status"] == "niepotwierdzony" and p["zgloszono"] < cutoff:
                p["status"] = "wygasly"
        for pid in list(self.state["zaprzeczenia"]):
            self._hide_known(pid)
        for pid in list(self.state["ukryte_osm"]):
            if not self._should_hide(pid):
                del self.state["ukryte_osm"][pid]  # confirmations caught up: back on the map


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
    hidden = set((state.get("ukryte_osm") or {}).keys())
    votes_all = state.get("glosy") or {}
    out = []
    for park in parks:
        if park["id"] in hidden:
            continue
        park = dict(park)
        for feature, votes in votes_all.get(park["id"], {}).items():
            if feature in FEATURES:
                park[feature] = tally(park.get(feature), votes)
        out.append(park)

    for pid, p in (state.get("nowe") or {}).items():
        if p["status"] not in ("niepotwierdzony", "potwierdzony"):
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
            park[feature] = tally(None, votes_all.get(pid, {}).get(feature, {}))
        out.append(park)
    return out
