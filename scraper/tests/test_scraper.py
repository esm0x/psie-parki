"""Offline tests: normalisation, admin areas, corrections and the contract.

    python -m unittest discover -s scraper/tests -t scraper
"""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import contract  # noqa: E402
from build import parks_from  # noqa: E402
from manual import apply_manual  # noqa: E402
from normalize import fenced, park_from, place_of, ring_area, surface, tri  # noqa: E402

# A square of about 30 × 30 m in Rybnik.
SQUARE = [
    {"lat": 50.1000, "lon": 18.5000},
    {"lat": 50.1000, "lon": 18.5004203},
    {"lat": 50.1002698, "lon": 18.5004203},
    {"lat": 50.1002698, "lon": 18.5000},
    {"lat": 50.1000, "lon": 18.5000},
]


def raw(*elements):
    return {"osm3s": {"timestamp_osm_base": "2026-10-09T01:00:00Z"}, "elements": list(elements)}


class Normalize(unittest.TestCase):
    def test_three_valued_features(self):
        self.assertTrue(tri({"lit": "yes"}, "lit"))
        self.assertFalse(tri({"lit": "no"}, "lit"))
        self.assertIsNone(tri({}, "lit"))
        # lit=24/7 or sunset-sunrise is a lamp with a schedule, which is a yes
        self.assertTrue(tri({"lit": "sunset-sunrise"}, "lit"))

    def test_fence_from_any_of_its_spellings(self):
        self.assertTrue(fenced({"barrier": "fence"}))
        self.assertTrue(fenced({"fenced": "yes"}))
        self.assertFalse(fenced({"fence": "no"}))
        self.assertIsNone(fenced({"barrier": "hedge"}))

    def test_surface_in_polish_and_unknowns_dropped(self):
        self.assertEqual(surface({"surface": "grass;sand"}), "trawa, piasek")
        self.assertIsNone(surface({"surface": "unobtainium"}))

    def test_area_of_a_thirty_metre_square(self):
        area = ring_area([(p["lat"], p["lon"]) for p in SQUARE])
        self.assertAlmostEqual(area, 900, delta=15)

    def test_admin_prefixes_are_stripped(self):
        self.assertEqual(
            place_of({"gmina": "gmina Lubomia", "miejscowosc": "Syrynia", "wojewodztwo": "województwo śląskie"}),
            ("Syrynia", None, "śląskie"),
        )
        # A city gmina has no level-8 boundary: the gmina is the city.
        self.assertEqual(
            place_of({"gmina": "Rybnik", "dzielnica": "Maroko-Nowiny"}),
            ("Rybnik", "Maroko-Nowiny", None),
        )
        # Overpass says "< multiple values found >" for a park across a boundary.
        self.assertEqual(place_of({"gmina": "Rybnik", "dzielnica": "< multiple values found >"})[1], None)

    def test_names_that_only_say_dog_park_are_dropped(self):
        for generic in ("Wybieg dla psów", "Psi wybieg", "Psi park", "Dog park"):
            el = {"type": "node", "id": 1, "lat": 50.1, "lon": 18.5, "tags": {"leisure": "dog_park", "name": generic}}
            self.assertIsNone(park_from(el)["name"], generic)
        el = {"type": "node", "id": 1, "lat": 50.1, "lon": 18.5, "tags": {"leisure": "dog_park", "name": "Górka dla Burka"}}
        self.assertEqual(park_from(el)["name"], "Górka dla Burka")

    def test_private_parks_are_left_out(self):
        el = {"type": "node", "id": 1, "lat": 50.1, "lon": 18.5, "tags": {"leisure": "dog_park", "access": "private"}}
        self.assertIsNone(park_from(el))

    def test_way_gets_centre_and_area(self):
        el = {"type": "way", "id": 7, "geometry": SQUARE, "tags": {"leisure": "dog_park", "name": " Psi  zakątek ", "barrier": "fence"}}
        p = park_from(el, {"gmina": "Rybnik"})
        self.assertEqual(p["id"], "way/7")
        self.assertEqual(p["name"], "Psi zakątek")
        self.assertEqual(p["locality"], "Rybnik")
        self.assertTrue(p["fenced"])
        self.assertAlmostEqual(p["areaM2"], 900, delta=15)
        self.assertAlmostEqual(p["lat"], 50.100135, places=5)


class Build(unittest.TestCase):
    def test_adm_elements_are_matched_to_their_park(self):
        doc = raw(
            {"type": "node", "id": 5655915525, "lat": 50.1026557, "lon": 18.5159636,
             "tags": {"leisure": "dog_park", "name": "Górka dla Burka"}},
            {"type": "adm", "id": 0, "tags": {"ref": "node/5655915525", "gmina": "Rybnik",
                                             "dzielnica": "Maroko-Nowiny", "wojewodztwo": "województwo śląskie"}},
            {"type": "node", "id": 2, "lat": 50.0, "lon": 18.0, "tags": {"leisure": "playground"}},
        )
        parks = parks_from(doc)
        self.assertEqual(len(parks), 1)
        self.assertEqual((parks[0]["locality"], parks[0]["district"], parks[0]["region"]),
                         ("Rybnik", "Maroko-Nowiny", "śląskie"))

    def test_output_passes_the_contract(self):
        el = {"type": "way", "id": 7, "geometry": SQUARE, "tags": {"leisure": "dog_park"}}
        parks = parks_from(raw(el))
        doc = {"generated_at": "x", "count": 1, "failures": [], "parks": parks}
        self.assertEqual(contract.check(doc), [])


class Manual(unittest.TestCase):
    def setUp(self):
        self.parks = parks_from(raw(
            {"type": "node", "id": 1, "lat": 50.1, "lon": 18.5, "tags": {"leisure": "dog_park"}},
            {"type": "node", "id": 2, "lat": 50.2, "lon": 18.6, "tags": {"leisure": "dog_park"}},
        ))

    def test_changes_hides_and_additions(self):
        manual = {
            "zmiany": {"node/1": {"fenced": True, "note": "Dni adopcji w pierwszą niedzielę"}},
            "ukryte": {"node/2": "zlikwidowany"},
            "dodane": [{"id": "manual/nowy-wybieg", "lat": 52.2, "lon": 21.0, "name": "Nowy"}],
        }
        result, failures = apply_manual(self.parks, manual)
        ids = [p["id"] for p in result]
        self.assertEqual(ids, ["node/1", "manual/nowy-wybieg"])
        self.assertTrue(result[0]["fenced"])
        self.assertEqual(result[1]["source"], "manual")
        self.assertEqual(failures, [])
        doc = {"generated_at": "x", "count": len(result), "failures": [], "parks": result}
        self.assertEqual(contract.check(doc), [])

    def test_bad_entries_are_reported_not_fatal(self):
        manual = {
            "zmiany": {"way/999": {"fenced": True}, "node/1": {"lat": 0, "colour": "red"}},
            "dodane": [{"id": "Zła nazwa", "lat": 1, "lon": 2}, {"id": "manual/bez-wspolrzednych"}],
        }
        result, failures = apply_manual(self.parks, manual)
        self.assertEqual(len(result), 2)
        self.assertEqual(result[0]["lat"], 50.1)  # lat is not editable on an OSM park
        self.assertEqual(len(failures), 4)


class Contract(unittest.TestCase):
    def test_swapped_coordinates_are_caught(self):
        p = parks_from(raw({"type": "node", "id": 1, "lat": 50.1, "lon": 18.5, "tags": {"leisure": "dog_park"}}))[0]
        p["lat"], p["lon"] = p["lon"], p["lat"]
        problems = contract.check({"generated_at": "x", "count": 1, "failures": [], "parks": [p]})
        self.assertTrue(any("not in Poland" in x for x in problems))


if __name__ == "__main__":
    unittest.main()
