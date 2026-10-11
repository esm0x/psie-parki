"""Talking to Overpass: one query, several servers, and patience.

Overpass instances are volunteer-run and fall over under load. The main one
(overpass-api.de) is the first choice; the mirrors are there for the day it answers
429 or 504. A query that times out on one server usually succeeds on the next, and a
server that answers with an HTML error page instead of JSON is treated as down.
"""

from __future__ import annotations

import sys
import time
from typing import Any, Iterable

import requests

ENDPOINTS = [
    "https://overpass-api.de/api/interpreter",
    "https://overpass.private.coffee/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
    "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
]

# Overpass asks clients to identify themselves; an anonymous UA gets throttled first.
UA = "psie-parki/1.0 (+https://github.com/esm0x/psie-parki; office@admin-stack.com)"


class OverpassError(RuntimeError):
    pass


def run(query: str, endpoints: Iterable[str] = ENDPOINTS, rounds: int = 3,
        timeout: int = 900, pause: float = 30.0) -> dict[str, Any]:
    """Runs `query` on the first server that answers with JSON; raises when none does."""
    errors: list[str] = []
    endpoints = list(endpoints)
    for attempt in range(1, rounds + 1):
        for url in endpoints:
            try:
                r = requests.post(url, data={"data": query}, headers={"User-Agent": UA},
                                  timeout=timeout)
                if r.status_code != 200:
                    raise OverpassError(f"HTTP {r.status_code}")
                body = r.json()
                # A query that ran out of time or memory still returns 200, with the
                # reason in "remark" and a truncated element list. Half a country of dog
                # parks looks plausible, so it must not pass as a result.
                remark = body.get("remark") or ""
                if "runtime error" in remark or "timed out" in remark:
                    raise OverpassError(remark.strip())
                print(f"[info] overpass: {url} answered on round {attempt}", file=sys.stderr)
                return body
            except (requests.RequestException, ValueError, OverpassError) as e:
                errors.append(f"{url}: {e}")
                print(f"[warn] overpass: {url} failed ({e})", file=sys.stderr)
        if attempt < rounds:
            time.sleep(pause * attempt)
    raise OverpassError("no Overpass server answered: " + "; ".join(errors[-len(endpoints):]))
