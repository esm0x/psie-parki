package com.adminstack.psieparki.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDateTime

class OpeningHoursTest {
    // 2026-10-12 is a Monday.
    private fun at(day: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 10, 11 + day, h, m)

    @Test
    fun `weekday hours with a Saturday rule`() {
        val oh = OpeningHours.parse("Mo-Fr 08:00-20:00; Sa 09:00-14:00")!!
        assertEquals("Otwarte do 20:00", describe(oh.status(at(1, 10)), at(1, 10)))
        assertEquals("Zamknięte · otworzy jutro o 8:00", describe(oh.status(at(1, 21)), at(1, 21)))
        assertEquals("Zamknięte · otworzy jutro o 8:00", describe(oh.status(at(7, 12)), at(7, 12)))         // Sunday
        assertEquals("Zamknięte · otworzy w poniedziałek o 8:00", describe(oh.status(at(6, 15)), at(6, 15))) // Saturday
        assertEquals("Otwarte do 14:00", describe(oh.status(at(6, 9)), at(6, 9)))
    }

    @Test
    fun `round the clock and overnight spans`() {
        val allDay = OpeningHours.parse("24/7")!!
        assertEquals("Otwarte całą dobę", describe(allDay.status(at(3, 3)), at(3, 3)))
        val night = OpeningHours.parse("Mo-Su 20:00-02:00")!!
        assertTrue(night.status(at(1, 1)) is OpeningHours.Status.Open)   // Monday 1:00, from Sunday night
        assertTrue(night.status(at(1, 12)) is OpeningHours.Status.Closed)
    }

    @Test
    fun `later rules win, off and split hours`() {
        val oh = OpeningHours.parse("Mo-Fr 10:00-13:00,15:00-19:00; We off")!!
        assertTrue(oh.status(at(1, 14)) is OpeningHours.Status.Closed)
        assertTrue(oh.status(at(1, 16)) is OpeningHours.Status.Open)
        assertTrue(oh.status(at(3, 11)) is OpeningHours.Status.Closed)    // Wednesday
    }

    @Test
    fun `what it does not understand it does not guess`() {
        for (raw in listOf(null, "", "by appointment", "Jan-Mar Mo 10:00-12:00", "Mo-Fr 08:00-25:00",
            "Mo-Fr 08:00-20:00, Sa 09:00-14:00", "sunrise-sunset", "off", "Xx 10:00-12:00")) {
            assertNull(raw, OpeningHours.parse(raw))
        }
    }
}

class HostileDataTest {
    @Test
    fun `a hostile parks file is cut down to what is safe to show`() {
        val evil = """{"count":3,"parks":[
            {"id":"node/1","lat":50.0,"lon":19.0,"name":"${"x".repeat(5000)}","website":"javascript:alert(1)"},
            {"id":"node/1","lat":50.0,"lon":19.0},
            {"id":"node/2","lat":1e308,"lon":19.0},
            {"id":"${"a".repeat(200)}","lat":50.0,"lon":19.0},
            {"id":"node/3","lat":50.0,"lon":19.0,"fenced":null,"unknown":{"deep":[[[[1]]]]}}
        ]}"""
        val f = parksJson.decodeFromString(ParksFile.serializer(), evil).sanitized()
        assertEquals(listOf("node/1", "node/3"), f.parks.map { it.id })
        assertEquals(120, f.parks[0].name!!.length)
        assertNull(f.parks[0].website)
        assertNull(f.parks[1].fenced)
    }

    @Test(expected = Exception::class)
    fun `a wrong type anywhere rejects the whole file, so the good cache stays`() {
        parksJson.decodeFromString(ParksFile.serializer(), """{"parks":[{"id":"node/1","lat":50.0,"lon":19.0,"fenced":"maybe"}]}""")
    }

    @Test(expected = Exception::class)
    fun `NaN is refused by the parser, which the cache treats as a failed download`() {
        parksJson.decodeFromString(ParksFile.serializer(), """{"parks":[{"id":"node/1","lat":NaN,"lon":19.0}]}""")
    }
}

class HelpersTest {
    private val file = File("../data/vets.json").takeIf { it.exists() } ?: File("data/vets.json")

    @Test
    fun `data vets json deserializes and has the vets the app promises`() {
        val f = parksJson.decodeFromString(HelpersFile.serializer(), file.readText()).sanitized()
        val vets = f.places.filter { it.isVet }
        assertTrue("vets: ${vets.size}", vets.size > 1000)
        assertTrue(f.places.any { !it.isVet })
        assertTrue(vets.count { it.hours != null } > 300)   // the parser understands most hours
        assertTrue(vets.filter { it.allDay }.all { it.hours?.alwaysOpen == true })
    }

    @Test
    fun `phone numbers become dial uris`() {
        val h = Helper("node/1", "weterynarz", lat = 50.0, lon = 19.0, phone = "+48 32 422 00 00")
        assertEquals("tel:+48324220000", h.dialUri)
        assertNull(h.copy(phone = "123").dialUri)
    }
}

class FavouritesTest {
    @Test
    fun `only favourites when the filter is on`() {
        val parks = listOf(Park("node/1", lat = 50.0, lon = 19.0), Park("node/2", lat = 50.1, lon = 19.0))
        assertEquals(listOf("node/2"), rank(parks, ParkQuery(onlyIds = setOf("node/2")), null).map { it.park.id })
        assertEquals(2, rank(parks, ParkQuery(), null).size)
        assertTrue(rank(parks, ParkQuery(onlyIds = emptySet()), null).isEmpty())
    }
}
