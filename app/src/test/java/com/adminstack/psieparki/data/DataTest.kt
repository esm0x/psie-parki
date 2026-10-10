package com.adminstack.psieparki.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GeoTest {
    @Test
    fun `distance between Rybnik market square and Katowice is about 39 km`() {
        // 38.7 km as the crow flies, by any online great-circle calculator
        val d = distanceMeters(LatLon(50.0971, 18.5418), LatLon(50.2599, 19.0216))
        assertEquals(38_670.0, d, 300.0)
    }

    @Test
    fun `distances read the Polish way`() {
        assertEquals("350 m", formatDistance(347.0))
        assertEquals("2,4 km", formatDistance(2_412.0))
        assertEquals("38 km", formatDistance(37_800.0))
    }

    @Test
    fun `areas are rounded to what a sketch on a map can claim`() {
        assertEquals("230 m²", formatArea(228))
        assertEquals("1 500 m²", formatArea(1_517))
        assertEquals("1,2 ha", formatArea(12_345))
        assertEquals("2 ha", formatArea(20_100))
    }

    @Test
    fun `search ignores case and Polish letters`() {
        assertEquals("lodz", foldForSearch("Łódź"))
        assertEquals("zory", foldForSearch("Żory"))
    }
}

class FiltersTest {
    private val rybnik = LatLon(50.0971, 18.5418)
    private val parks = listOf(
        Park("node/1", "Górka dla Burka", 50.1027, 18.5160, locality = "Rybnik", district = "Maroko-Nowiny", fenced = true, agility = true),
        Park("way/2", null, 50.0525, 18.5979, locality = "Rybnik", district = "Boguszowice Stare"),
        Park("way/3", null, 52.2297, 21.0122, locality = "Warszawa", fenced = false),
        Park("way/4", null, 52.4, 16.9, locality = "Nowa Wieś", region = "wielkopolskie"),
        Park("way/5", null, 50.3, 22.1, locality = "Nowa Wieś", region = "podkarpackie"),
    )

    @Test
    fun `nearest first when there is an origin`() {
        val ids = rank(parks, ParkQuery(), rybnik).map { it.park.id }
        assertEquals(listOf("node/1", "way/2"), ids.take(2))
        assertEquals("way/3", ids.last())
    }

    @Test
    fun `a filter passes only a confirmed yes`() {
        val fenced = rank(parks, ParkQuery(features = setOf(Feature.FENCED)), null).map { it.park.id }
        assertEquals(listOf("node/1"), fenced)
    }

    @Test
    fun `text matches district without diacritics`() {
        val hits = rank(parks, ParkQuery(text = "boguszowice"), null).map { it.park.id }
        assertEquals(listOf("way/2"), hits)
    }

    @Test
    fun `places with the same name carry their voivodeship`() {
        val labels = localities(parks).map { it.label }
        assertTrue("Nowa Wieś (wielkopolskie)" in labels)
        assertTrue("Nowa Wieś (podkarpackie)" in labels)
        assertTrue("Rybnik" in labels)
        assertEquals(2, localities(parks).first { it.label == "Rybnik" }.parkCount)
    }
}

class ReportTest {
    @Test
    fun `a correction names the park well enough to find it in OSM`() {
        val park = Park("way/808455741", "Psistanek", 50.088718, 18.521994, locality = "Rybnik", district = "Smolna")
        val mail = Report.correction(park, "1.0.0")
        assertEquals("office@admin-stack.com", mail.to)
        assertEquals("Psie Parki: poprawka – Psistanek, Rybnik, Smolna", mail.subject)
        assertTrue(mail.body.contains("Id: way/808455741"))
        assertTrue(mail.body.contains("Współrzędne: 50.088718, 18.521994"))
        assertTrue(mail.body.contains("https://www.openstreetmap.org/way/808455741"))
    }

    @Test
    fun `a missing-park report works without a location`() {
        val mail = Report.missing(null, "1.0.0")
        assertTrue(!mail.body.contains("Okolica"))
    }
}

/** The file the scraper wrote, read by the same code the app uses: the contract, live. */
class ContractTest {
    private val file = File("../data/parks.json").takeIf { it.exists() } ?: File("data/parks.json")

    @Test
    fun `data parks json deserializes completely`() {
        val parsed = parksJson.decodeFromString(ParksFile.serializer(), file.readText())
        assertEquals(parsed.count, parsed.parks.size)
        assertTrue("expected hundreds of parks, got ${parsed.parks.size}", parsed.parks.size > 300)
        assertTrue(parsed.parks.all { it.lat in 48.9..55.0 && it.lon in 14.0..24.2 })
        assertEquals(parsed.parks.size, parsed.parks.map { it.id }.toSet().size)
    }

    @Test
    fun `unknown values fall back instead of failing`() {
        val parsed = parksJson.decodeFromString(
            ParksFile.serializer(),
            """{"count":1,"parks":[{"id":"node/1","lat":50.0,"lon":19.0,"fenced":null,"newField":"x"}]}""",
        )
        assertNull(parsed.parks.single().fenced)
    }
}
