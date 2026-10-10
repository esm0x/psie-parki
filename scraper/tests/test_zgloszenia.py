"""The rules that decide what submissions from the app do to the map."""

from __future__ import annotations

import sys
import unittest
from datetime import date
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import contract  # noqa: E402
from moderuj import blocked_by, record_of  # noqa: E402
from zgloszenia import EMPTY_STATE, Moderator, community_layer, tally  # noqa: E402

GORKA = {"id": "node/5655915525", "name": "Górka dla Burka", "lat": 50.1026557, "lon": 18.5159636,
         "locality": "Rybnik", "district": "Maroko-Nowiny", "region": "śląskie", "fenced": None,
         "lit": None, "water": None, "agility": None, "smallDogArea": None}
FAR = (50.20, 18.70)  # ~15 km from Górka


def rec(typ, who="a", net="n1", **kw):
    return {"v": 1, "typ": typ, "autor": who, "siec": net, "cechy": kw.pop("cechy", {}),
            "nazwa": kw.pop("nazwa", None), **kw}


def new_park(who="a", net="n1", lat=FAR[0], lon=FAR[1], **kw):
    return rec("nowy", who, net, lat=lat, lon=lon, parkId=None, **kw)


class NewParks(unittest.TestCase):
    def setUp(self):
        self.mod = Moderator(dict(EMPTY_STATE), [GORKA], today=date(2026, 10, 10))

    def test_new_park_goes_on_the_map_unconfirmed(self):
        out = self.mod.apply(1, new_park(nazwa="Psi zakątek", cechy={"fenced": True}),
                             {"locality": "Gliwice", "region": "śląskie"})
        self.assertEqual((out.status, out.park_id), ("niepotwierdzone", "manual/z-1"))
        park = [p for p in community_layer([GORKA], self.mod.state) if p["id"] == "manual/z-1"][0]
        self.assertFalse(park["confirmed"])
        self.assertTrue(park["fenced"])
        self.assertEqual((park["name"], park["locality"], park["source"]), ("Psi zakątek", "Gliwice", "zgloszenie"))

    def test_a_new_park_next_to_a_known_one_is_that_park(self):
        out = self.mod.apply(1, new_park(lat=50.1027, lon=18.5162, cechy={"water": True}))
        self.assertEqual(out.park_id, "node/5655915525")
        self.assertIn("już jest na mapie", out.message)
        self.assertEqual(self.mod.state["nowe"], {})
        self.assertEqual(self.mod.state["glosy"]["node/5655915525"]["water"], {"a/n1": True})

    def test_confirmation_must_come_from_someone_else(self):
        self.mod.apply(1, new_park())
        same_device = self.mod.apply(2, rec("potwierdzam", parkId="manual/z-1"))
        same_wifi = self.mod.apply(3, rec("potwierdzam", who="b", parkId="manual/z-1"))
        self.assertEqual(self.mod.state["nowe"]["manual/z-1"]["status"], "niepotwierdzony")
        self.assertIn("innej osoby", same_device.message)
        self.assertIn("innej osoby", same_wifi.message)
        self.mod.apply(4, rec("potwierdzam", who="b", net="n2", parkId="manual/z-1"))
        self.assertEqual(self.mod.state["nowe"]["manual/z-1"]["status"], "potwierdzony")

    def test_three_waiting_parks_per_person(self):
        for i in range(3):
            self.mod.apply(i + 1, new_park(lat=50.2 + i * 0.01))
        out = self.mod.apply(9, new_park(lat=50.3))
        self.assertEqual((out.status, out.reason), ("odrzucone", "limit"))
        # someone else is not affected
        self.assertEqual(self.mod.apply(10, new_park(who="b", net="n2", lat=50.3)).status, "niepotwierdzone")

    def test_a_point_in_water_is_refused(self):
        out = self.mod.apply(1, new_park(), {"blocked": "to woda"})
        self.assertEqual((out.status, out.reason), ("odrzucone", "miejsce"))

    def test_place_borrowed_from_a_nearby_park_when_overpass_is_down(self):
        self.mod.apply(1, new_park(lat=50.1100, lon=18.5300))  # ~1.3 km from Górka
        self.assertEqual(self.mod.state["nowe"]["manual/z-1"]["locality"], "Rybnik")

    def test_each_issue_counts_once(self):
        self.mod.apply(1, new_park())
        again = self.mod.apply(1, new_park())
        self.assertIn("już przetworzone", again.message)
        self.assertEqual(len(self.mod.state["nowe"]), 1)

    def test_tampered_issue_is_refused(self):
        out = self.mod.apply(1, {"v": 1, "typ": "nowy", "autor": "a", "siec": "n", "lat": 52.5, "lon": 13.4})
        self.assertEqual((out.status, out.reason), ("odrzucone", "format"))
        self.assertEqual(self.mod.apply(2, {}).reason, "format")


class Removal(unittest.TestCase):
    def setUp(self):
        self.mod = Moderator(dict(EMPTY_STATE), [GORKA], today=date(2026, 10, 10))

    def visible(self):
        return {p["id"] for p in community_layer([GORKA], self.mod.state)}

    def test_unconfirmed_park_goes_after_one_independent_denial(self):
        self.mod.apply(1, new_park())
        self.mod.apply(2, rec("nie_istnieje", who="b", net="n2", parkId="manual/z-1"))
        self.assertNotIn("manual/z-1", self.visible())

    def test_confirmed_park_needs_more_denials_than_support(self):
        self.mod.apply(1, new_park())
        self.mod.apply(2, rec("potwierdzam", who="b", net="n2", parkId="manual/z-1"))
        self.mod.apply(3, rec("nie_istnieje", who="c", net="n3", parkId="manual/z-1"))
        self.assertIn("manual/z-1", self.visible())

    def test_osm_park_hidden_after_two_independent_reports(self):
        self.mod.apply(1, rec("nie_istnieje", parkId="node/5655915525"))
        self.mod.apply(2, rec("nie_istnieje", who="b", parkId="node/5655915525"))  # same network
        self.assertIn("node/5655915525", self.visible())
        self.mod.apply(3, rec("nie_istnieje", who="c", net="n3", parkId="node/5655915525"))
        self.assertNotIn("node/5655915525", self.visible())

    def test_confirmations_protect_a_known_park(self):
        for i, kind in enumerate(["potwierdzam", "potwierdzam", "nie_istnieje", "nie_istnieje"]):
            self.mod.apply(i, rec(kind, who=f"x{i}", net=f"n{i}", parkId="node/5655915525"))
        self.assertIn("node/5655915525", self.visible())

    def test_creator_can_take_their_park_back(self):
        self.mod.apply(1, new_park())
        self.mod.apply(2, rec("nie_istnieje", parkId="manual/z-1"))
        self.assertNotIn("manual/z-1", self.visible())


class Votes(unittest.TestCase):
    def test_unknown_features_take_the_first_vote(self):
        self.assertTrue(tally(None, {"a/1": True}))
        self.assertIsNone(tally(None, {"a/1": True, "b/2": False}))
        self.assertIsNone(tally(None, {}))

    def test_osm_values_need_two_agreeing_people_to_change(self):
        self.assertTrue(tally(True, {"a/1": False}))
        self.assertFalse(tally(True, {"a/1": False, "b/2": False}))
        self.assertTrue(tally(True, {"a/1": False, "b/2": False, "c/3": True, "d/4": True}))

    def test_a_changed_mind_replaces_the_vote(self):
        mod = Moderator(dict(EMPTY_STATE), [GORKA])
        mod.apply(1, rec("cechy", parkId="node/5655915525", cechy={"lit": True}))
        mod.apply(2, rec("cechy", parkId="node/5655915525", cechy={"lit": False}))
        park = community_layer([GORKA], mod.state)[0]
        self.assertFalse(park["lit"])


class Publishing(unittest.TestCase):
    def test_community_parks_pass_the_contract(self):
        base = dict(GORKA, areaM2=None, benches=None, bins=None, surface=None, openingHours=None,
                    operator=None, website=None, description=None, note=None, customersOnly=False,
                    confirmed=True, source="osm")
        mod = Moderator(dict(EMPTY_STATE), [base])
        mod.apply(1, new_park(nazwa="Psi zakątek", cechy={"lit": False}))
        parks = community_layer([base], mod.state)
        doc = {"generated_at": "x", "count": len(parks), "failures": [], "parks": parks}
        self.assertEqual(contract.check(doc), [])


class Issues(unittest.TestCase):
    def test_record_is_read_from_the_json_block(self):
        issue = {"body": 'Zgłoszenie…\n\n```json\n{"v": 1, "typ": "nowy"}\n```'}
        self.assertEqual(record_of(issue), {"v": 1, "typ": "nowy"})
        self.assertIsNone(record_of({"body": "edited by hand"}))

    def test_blockers(self):
        self.assertEqual(blocked_by([{"type": "area", "tags": {"natural": "water"}}]), "to woda")
        self.assertEqual(blocked_by([{"type": "area", "tags": {"building": "yes"}}]), "to budynek")
        self.assertIsNone(blocked_by([{"type": "area", "tags": {"leisure": "park"}}]))
        self.assertIsNone(blocked_by([{"type": "area", "tags": {"boundary": "administrative", "admin_level": "8"}}]))


if __name__ == "__main__":
    unittest.main()
