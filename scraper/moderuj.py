#!/usr/bin/env python3
"""The moderation run: open submission issues → decisions → data/parks.json.

    python scraper/moderuj.py decyduj --wyniki /tmp/wyniki.json
    python scraper/moderuj.py oglos   --wyniki /tmp/wyniki.json

`decyduj` reads every open issue labelled "zgloszenie" that the worker's account opened,
applies scraper/zgloszenia.py to each in order, saves manual/zgloszenia.json and
republishes data/parks.json. It changes nothing on GitHub, so when the commit after it
fails, the next run simply decides the same issues again. `oglos` runs after the
commit: it comments the decision on each issue, labels it and closes it. The worker
reads those labels to tell the app what happened to a submission.

Environment: GITHUB_TOKEN, GITHUB_REPOSITORY, ZGLOSZENIA_BOT (login the worker's token
belongs to; issues from anyone else are ignored, so a stranger cannot inject one).
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from pathlib import Path
from typing import Any, Optional

import requests

sys.path.insert(0, str(Path(__file__).resolve().parent))

import build  # noqa: E402
import overpass  # noqa: E402
from normalize import place_of  # noqa: E402
from zgloszenia import EMPTY_STATE, Moderator, Outcome  # noqa: E402

API = "https://api.github.com"
JSON_BLOCK = re.compile(r"```json\s*(\{.*?\})\s*```", re.S)
MAX_PER_RUN = 200


class GitHub:
    def __init__(self, token: str, repo: str):
        self.repo = repo
        self.s = requests.Session()
        self.s.headers.update({
            "authorization": f"Bearer {token}",
            "accept": "application/vnd.github+json",
            "x-github-api-version": "2022-11-28",
            "user-agent": "psie-parki-moderacja",
        })

    def open_submissions(self) -> list[dict[str, Any]]:
        issues, page = [], 1
        while len(issues) < MAX_PER_RUN:
            r = self.s.get(f"{API}/repos/{self.repo}/issues", params={
                "labels": "zgloszenie", "state": "open", "sort": "created",
                "direction": "asc", "per_page": 100, "page": page,
            }, timeout=30)
            r.raise_for_status()
            batch = [i for i in r.json() if "pull_request" not in i]
            issues += batch
            if len(r.json()) < 100:
                break
            page += 1
        return issues[:MAX_PER_RUN]

    def announce(self, nr: int, outcome: dict[str, Any]) -> None:
        labels = [outcome["status"]] + ([f"powod:{outcome['reason']}"] if outcome.get("reason") else [])
        body = outcome["message"] + "\n\n---\n_Automatyczna moderacja Psie Parki._"
        self.s.post(f"{API}/repos/{self.repo}/issues/{nr}/comments", json={"body": body}, timeout=30).raise_for_status()
        self.s.post(f"{API}/repos/{self.repo}/issues/{nr}/labels", json={"labels": labels}, timeout=30).raise_for_status()
        reason = "not_planned" if outcome["status"] == "odrzucone" else "completed"
        self.s.patch(f"{API}/repos/{self.repo}/issues/{nr}",
                     json={"state": "closed", "state_reason": reason}, timeout=30).raise_for_status()


def record_of(issue: dict[str, Any]) -> Optional[dict[str, Any]]:
    m = JSON_BLOCK.search(issue.get("body") or "")
    if not m:
        return None
    try:
        return json.loads(m.group(1))
    except json.JSONDecodeError:
        return None


# --------------------------------------------------------------------------- #
# what is at a point, from OSM
# --------------------------------------------------------------------------- #

CONTEXT_QUERY = """
[out:json][timeout:25];
is_in({lat},{lon})->.a;
area.a->.all;
.all out tags;
way(around:12,{lat},{lon})["highway"~"^(motorway|trunk|motorway_link|trunk_link)$"];
out tags;
way(around:4,{lat},{lon})["railway"~"^(rail|light_rail|subway|tram)$"];
out tags;
"""

LEVELS = {"4": "wojewodztwo", "7": "gmina", "8": "miejscowosc", "9": "dzielnica", "10": "osiedle"}


def blocked_by(elements: list[dict[str, Any]]) -> Optional[str]:
    """Why no dog park can be at this point, or None. Only the unambiguous cases."""
    for e in elements:
        t = e.get("tags") or {}
        if e.get("type") == "area":
            if t.get("natural") == "water" or "water" in t or t.get("landuse") == "reservoir":
                return "to woda"
            if t.get("building") and t.get("building") != "no" and t.get("leisure") != "dog_park":
                return "to budynek"
            if t.get("aeroway") in {"runway", "taxiway", "apron"}:
                return "to lotnisko"
        elif e.get("type") == "way":
            if "highway" in t:
                return "to droga szybkiego ruchu"
            if "railway" in t:
                return "to tory kolejowe"
    return None


def context_at(lat: float, lon: float) -> dict[str, Any]:
    """Admin areas and blockers at a point. Empty when Overpass is down: a submission
    then waits for nothing, it just goes on the map without those checks."""
    try:
        raw = overpass.run(CONTEXT_QUERY.format(lat=lat, lon=lon), rounds=1, timeout=60, pause=0)
    except overpass.OverpassError as e:
        print(f"[warn] no OSM context for {lat},{lon}: {e}", file=sys.stderr)
        return {}
    elements = raw.get("elements") or []
    adm = {}
    for e in elements:
        t = e.get("tags") or {}
        if e.get("type") == "area" and t.get("boundary") == "administrative" and t.get("admin_level") in LEVELS:
            adm.setdefault(LEVELS[t["admin_level"]], t.get("name"))
    locality, district, region = place_of(adm)
    return {"locality": locality, "district": district, "region": region, "blocked": blocked_by(elements)}


# --------------------------------------------------------------------------- #

def decide(gh: GitHub, bot: str, results_path: Path) -> int:
    state = json.loads(build.COMMUNITY.read_text(encoding="utf-8")) if build.COMMUNITY.exists() else dict(EMPTY_STATE)
    osm = json.loads(build.OSM_OUT.read_text(encoding="utf-8"))["parks"]
    manual = json.loads(build.MANUAL.read_text(encoding="utf-8")) if build.MANUAL.exists() else {}
    hidden_by_hand = set((manual.get("ukryte") or {}).keys())
    known = [p for p in osm if p["id"] not in hidden_by_hand] + [
        {"id": d["id"], "lat": d["lat"], "lon": d["lon"], "locality": d.get("locality"), "region": d.get("region")}
        for d in manual.get("dodane") or [] if "id" in d and "lat" in d and "lon" in d
    ]
    mod = Moderator(state, known)

    results = []
    for issue in gh.open_submissions():
        if (issue.get("user") or {}).get("login") != bot:
            print(f"[warn] #{issue['number']} not opened by {bot}; ignored", file=sys.stderr)
            continue
        rec = record_of(issue)
        context = {}
        if rec and rec.get("typ") == "nowy" and isinstance(rec.get("lat"), (int, float)):
            context = context_at(rec["lat"], rec["lon"])
        outcome: Outcome = mod.apply(issue["number"], rec or {}, context)
        print(f"[info] #{issue['number']} {rec and rec.get('typ')}: {outcome.status} {outcome.reason or ''}",
              file=sys.stderr)
        results.append({"nr": issue["number"], **outcome.__dict__})

    build.COMMUNITY.write_text(json.dumps(mod.state, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    results_path.write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
    if not results:
        print("[info] nothing to moderate", file=sys.stderr)
        return 0
    osm_doc = json.loads(build.OSM_OUT.read_text(encoding="utf-8"))
    return build.publish(osm_doc["parks"], osm_doc.get("osm_base"))


def announce(gh: GitHub, results_path: Path) -> int:
    failed = 0
    for outcome in json.loads(results_path.read_text(encoding="utf-8")):
        try:
            gh.announce(outcome["nr"], outcome)
        except requests.RequestException as e:
            failed += 1
            print(f"[error] #{outcome['nr']}: {e}", file=sys.stderr)
    return 1 if failed else 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("krok", choices=["decyduj", "oglos"])
    ap.add_argument("--wyniki", type=Path, required=True)
    args = ap.parse_args()
    gh = GitHub(os.environ["GITHUB_TOKEN"], os.environ["GITHUB_REPOSITORY"])
    if args.krok == "decyduj":
        return decide(gh, os.environ["ZGLOSZENIA_BOT"], args.wyniki)
    return announce(gh, args.wyniki)


if __name__ == "__main__":
    sys.exit(main())
