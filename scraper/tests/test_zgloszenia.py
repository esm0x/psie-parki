"""The rules that decide what submissions from the app do to the map, including the
attacks from the adversarial review: invented install ids, two taps that wipe a mapped
park, floods of fake parks."""

from __future__ import annotations

import sys
import unittest
from datetime import date, timedelta
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import contract  # noqa: E402
from moderuj import JSON_BLOCK, blocked_by, record_of  # noqa: E402
from zgloszenia import EMPTY_STATE, Moderator, community_layer, tally  # noqa: E402

GORKA = {"id": "node/5655915525", "name": "Górka dla Burka", "lat": 50.1026557, "lon": 18.5159636,
         "locality": "Rybnik", "district": "Maroko-Nowiny", "region": "śląskie", "fenced": True,
         "lit": None, "water": None, "agility": None, "smallDogArea": None}
FAR = (50.20, 18.70)  # ~15 km from Górka
DAY = date(2026, 10, 10)


def rec(typ, who="a", net="n1", **kw):
    return {"v": 1, "typ": typ, "autor": who, "siec": net, "cechy": kw.pop("cechy", {}),
            "nazwa": kw.pop("nazwa", None), **kw}


def new_park(who="a", net="n1", lat=FAR[0], lon=FAR[1], **kw):
    return rec("nowy", who, net, lat=lat, lon=lon, parkId=None, **kw)


class Day:
    """A moderator that can move to the next day with the same state, like the cron."""

    def __init__(self, known=(GORKA,)):
        self.known, self.state, self.today, self.nr = list(known), dict(EMPTY_STATE), DAY, 0

    def run(self, *records, context=None):
        mod = Moderator(self.state, self.known, today=self.today)
        outs = []
        for r in records:
            self.nr += 1
            outs.append(mod.apply(self.nr, r, context))
        mod.sweep()
        self.state = mod.state
        return outs if len(outs) != 1 else outs[0]

    def next_day(self, n=1):
        self.today += timedelta(days=n)
        return self

    def visible(self):
        return {p["id"]: p for p in community_layer(self.known, self.state)}


class NewParks(unittest.TestCase):
    def test_new_park_goes_on_the_map_unconfirmed(self):
        d = Day()
        out = d.run(new_park(nazwa="Psi zakątek", cechy={"lit": True}), context={"locality": "Gliwice", "region": "śląskie"})
        self.assertEqual((out.status, out.park_id), ("niepotwierdzone", "manual/z-1"))
        park = d.visible()["manual/z-1"]
        self.assertFalse(park["confirmed"])
        self.assertTrue(park["lit"])
        self.assertEqual((park["name"], park["locality"], park["source"]), ("Psi zakątek", "Gliwice", "zgloszenie"))

    def test_a_new_park_next_to_a_known_one_is_that_park(self):
        d = Day()
        out = d.run(new_park(lat=50.1027, lon=18.5162, cechy={"water": True}))
        self.assertEqual(out.park_id, "node/5655915525")
        self.assertIn("już jest na mapie", out.message)
        self.assertEqual(d.state["nowe"], {})
        self.assertEqual(d.state["glosy"]["node/5655915525"]["water"], {"n1": True})

    def test_confirmation_from_another_network_on_a_later_day(self):
        d = Day()
        d.run(new_park())
        self.assertIn("innej osoby", d.run(rec("potwierdzam", who="b", parkId="manual/z-1")).message)        # same Wi-Fi
        self.assertIn("następnego dnia", d.run(rec("potwierdzam", who="b", net="n2", parkId="manual/z-1")).message)
        self.assertEqual(d.state["nowe"]["manual/z-1"]["status"], "niepotwierdzony")
        d.next_day().run(rec("potwierdzam", who="b", net="n2", parkId="manual/z-1"))
        self.assertTrue(d.visible()["manual/z-1"]["confirmed"])

    def test_invented_install_ids_do_not_beat_the_network_limits(self):
        d = Day()
        outs = d.run(*[new_park(who=f"uuid{i}", lat=50.2 + i * 0.01) for i in range(5)])
        self.assertEqual([o.status for o in outs], ["niepotwierdzone"] * 2 + ["odrzucone"] * 3)
        d.next_day().run(new_park(who="x", lat=50.4))
        out = d.run(new_park(who="y", lat=50.45))
        self.assertEqual((out.status, out.reason), ("odrzucone", "limit"))  # 3 waiting from n1

    def test_country_wide_daily_cap(self):
        d = Day()
        outs = d.run(*[new_park(who=f"u{i}", net=f"n{i}", lat=49.5 + i * 0.1) for i in range(25)])
        self.assertEqual(sum(o.status == "niepotwierdzone" for o in outs), 20)

    def test_unconfirmed_parks_expire(self):
        d = Day()
        d.run(new_park())
        d.next_day(89).run()
        self.assertIn("manual/z-1", d.visible())
        d.next_day(2).run()
        self.assertNotIn("manual/z-1", d.visible())

    def test_a_point_in_water_is_refused(self):
        out = Day().run(new_park(), context={"blocked": "to woda"})
        self.assertEqual((out.status, out.reason), ("odrzucone", "miejsce"))

    def test_a_hidden_spot_cannot_be_refilled(self):
        d = Day()
        d.run(new_park())
        d.run(rec("nie_istnieje", who="b", net="n2", parkId="manual/z-1"))
        out = d.run(new_park(who="c", net="n3", lat=FAR[0] + 0.0001))
        self.assertEqual((out.status, out.reason), ("odrzucone", "ukryty"))

    def test_place_borrowed_from_a_nearby_park_when_overpass_is_down(self):
        d = Day()
        d.run(new_park(lat=50.1100, lon=18.5300))  # ~1.3 km from Górka
        self.assertEqual(d.state["nowe"]["manual/z-1"]["locality"], "Rybnik")

    def test_a_rerun_repeats_the_first_decision(self):
        mod = Moderator(dict(EMPTY_STATE), [GORKA], today=DAY)
        first = mod.apply(1, new_park(), {"blocked": "to woda"})
        again = mod.apply(1, new_park(), {})
        self.assertEqual((again.status, again.reason), (first.status, first.reason))

    def test_tampered_issue_is_refused(self):
        mod = Moderator(dict(EMPTY_STATE), [GORKA], today=DAY)
        self.assertEqual(mod.apply(1, {"v": 1, "typ": "nowy", "autor": "a", "siec": "n", "lat": 52.5, "lon": 13.4}).reason, "format")
        self.assertEqual(mod.apply(2, {"v": 1, "typ": "nowy", "autor": "a", "siec": "n", "lat": 50.1}).reason, "format")
        self.assertEqual(mod.apply(3, {}).reason, "format")
        self.assertEqual(mod.apply(4, new_park(nazwa="Park @esm0x")).reason, "format")


class Removal(unittest.TestCase):
    def test_unconfirmed_park_goes_after_one_report_from_another_network(self):
        d = Day()
        d.run(new_park())
        d.run(rec("nie_istnieje", who="b", parkId="manual/z-1"))  # same network: ignored
        self.assertIn("manual/z-1", d.visible())
        d.run(rec("nie_istnieje", who="b", net="n2", parkId="manual/z-1"))
        self.assertNotIn("manual/z-1", d.visible())

    def test_creator_can_take_their_park_back(self):
        d = Day()
        d.run(new_park())
        d.run(rec("nie_istnieje", parkId="manual/z-1"))
        self.assertNotIn("manual/z-1", d.visible())

    def test_two_taps_do_not_wipe_a_mapped_park(self):
        d = Day()
        d.run(rec("nie_istnieje", who="u1", net="n1", parkId=GORKA["id"]),
              rec("nie_istnieje", who="u2", net="n2", parkId=GORKA["id"]),
              rec("nie_istnieje", who="u3", net="n3", parkId=GORKA["id"]))
        self.assertIn(GORKA["id"], d.visible())          # three networks, but one day
        d.next_day().run(rec("nie_istnieje", who="u4", net="n4", parkId=GORKA["id"]))
        self.assertNotIn(GORKA["id"], d.visible())       # three+ networks over two days

    def test_confirmations_bring_a_mapped_park_back(self):
        d = Day()
        d.run(*[rec("nie_istnieje", who=f"u{i}", net=f"n{i}", parkId=GORKA["id"]) for i in range(3)])
        d.next_day().run(rec("nie_istnieje", who="u9", net="n9", parkId=GORKA["id"]))
        self.assertNotIn(GORKA["id"], d.visible())
        d.next_day().run(*[rec("potwierdzam", who=f"c{i}", net=f"c{i}", parkId=GORKA["id"]) for i in range(4)])
        self.assertIn(GORKA["id"], d.visible())

    def test_at_most_three_mapped_parks_hide_per_day(self):
        parks = [dict(GORKA, id=f"node/{i}", lat=50 + i * 0.1) for i in range(5)]
        d = Day(parks)
        d.run(*[rec("nie_istnieje", who=f"u{i}{j}", net=f"n{j}", parkId=f"node/{i}") for i in range(5) for j in range(3)])
        d.next_day().run(*[rec("nie_istnieje", who=f"v{i}", net="n9", parkId=f"node/{i}") for i in range(5)])
        self.assertEqual(len(parks) - len(d.visible()), 3)
        d.next_day().run()
        self.assertEqual(len(d.visible()), 0)            # the rest the day after


class Votes(unittest.TestCase):
    def test_unknown_features_take_the_first_vote(self):
        self.assertTrue(tally(None, {"n1": True}))
        self.assertIsNone(tally(None, {"n1": True, "n2": False}))
        self.assertIsNone(tally(None, {}))

    def test_osm_values_need_two_networks_to_change(self):
        self.assertTrue(tally(True, {"n1": False}))
        self.assertFalse(tally(True, {"n1": False, "n2": False}))
        self.assertTrue(tally(True, {"n1": False, "n2": False, "n3": True, "n4": True}))

    def test_two_installs_on_one_network_are_one_vote(self):
        d = Day()
        d.run(rec("cechy", who="x1", parkId=GORKA["id"], cechy={"fenced": False}),
              rec("cechy", who="x2", parkId=GORKA["id"], cechy={"fenced": False}))
        self.assertTrue(d.visible()[GORKA["id"]]["fenced"])   # OSM says fenced; one network disagrees

    def test_a_changed_mind_replaces_the_vote(self):
        d = Day()
        d.run(rec("cechy", parkId=GORKA["id"], cechy={"lit": True}),
              rec("cechy", parkId=GORKA["id"], cechy={"lit": False}))
        self.assertFalse(d.visible()[GORKA["id"]]["lit"])


class Publishing(unittest.TestCase):
    def test_community_parks_pass_the_contract(self):
        base = dict(GORKA, areaM2=None, benches=None, bins=None, surface=None, openingHours=None,
                    operator=None, website=None, description=None, note=None, customersOnly=False,
                    confirmed=True, source="osm")
        d = Day([base])
        d.run(new_park(nazwa="Psi zakątek", cechy={"lit": False}))
        parks = list(d.visible().values())
        doc = {"generated_at": "x", "count": len(parks), "failures": [], "parks": parks}
        self.assertEqual(contract.check(doc), [])


class Issues(unittest.TestCase):
    def test_record_is_read_from_the_last_json_fence(self):
        issue = {"body": 'Zgłoszenie…\n\n```json\n{"v": 1, "typ": "nowy", "nazwa": "x} ``` y"}\n```'}
        self.assertEqual(record_of(issue)["nazwa"], "x} ``` y")
        self.assertIsNone(record_of({"body": "edited by hand"}))
        self.assertIsNotNone(JSON_BLOCK)

    def test_blockers(self):
        self.assertEqual(blocked_by([{"type": "area", "tags": {"natural": "water"}}]), "to woda")
        self.assertEqual(blocked_by([{"type": "area", "tags": {"building": "yes"}}]), "to budynek")
        self.assertIsNone(blocked_by([{"type": "area", "tags": {"leisure": "park"}}]))
        self.assertIsNone(blocked_by([{"type": "area", "tags": {"boundary": "administrative", "admin_level": "8"}}]))


if __name__ == "__main__":
    unittest.main()
