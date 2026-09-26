package app.locjournal

import app.locjournal.geo.GeoUtils
import app.locjournal.geo.PlaceArea
import app.locjournal.geo.PlaceCandidate
import app.locjournal.geo.PlaceMatcher
import app.locjournal.geo.PlaceNaming
import app.locjournal.io.Csv
import app.locjournal.util.DaySplitter
import app.locjournal.util.TimeUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class UtilsTest {
    @Test
    fun haversineDistance() {
        // 0.001 degree of latitude is ~111 m
        val d = GeoUtils.distanceMeters(52.0, 13.0, 52.001, 13.0)
        assertEquals(111.2, d, 0.5)
    }

    @Test
    fun placeMatcherAllowsDriftAndPrefersClosest() {
        val home = PlaceArea(1, 52.0, 13.0, 100f)
        val shop = PlaceArea(2, 52.004, 13.0, 100f)
        val places = listOf(home, shop)
        // 130 m from home: outside 100 m radius, but a 40 m accuracy fix is allowed
        assertEquals(home, PlaceMatcher.match(places, 52.00117, 13.0, 40f) { it })
        assertNull(PlaceMatcher.match(places, 52.00117, 12.997, 5f) { it })
        assertEquals(shop, PlaceMatcher.match(places, 52.0033, 13.0, 5f) { it })
        // Overlapping places: the one whose center is relatively closer wins
        val campus = PlaceArea(3, 52.002, 13.0, 600f)
        assertEquals(home, PlaceMatcher.match(listOf(home, campus), 52.0001, 13.0, 5f) { it })
    }

    @Test
    fun daySplitterSplitsOvernightStay() {
        val zone = ZoneId.of("Europe/Berlin")
        val start = TimeUtils.toMillis(LocalDateTime.of(2026, 9, 1, 22, 0), zone)
        val end = TimeUtils.toMillis(LocalDateTime.of(2026, 9, 2, 8, 0), zone)
        val result = DaySplitter.split(listOf("home"), LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 2), zone) { start to end }
        assertEquals(2, result.size)
        val first = result[LocalDate.of(2026, 9, 1)]!!.single()
        val second = result[LocalDate.of(2026, 9, 2)]!!.single()
        assertEquals(2 * 3_600_000L, first.duration)
        assertEquals(8 * 3_600_000L, second.duration)
        assertTrue(first.continuesNextDay)
        assertTrue(second.continuedFromPreviousDay)
    }

    @Test
    fun daySplitterClipsToRangeAndKeepsZeroLengthVisits() {
        val zone = ZoneId.of("UTC")
        val t = TimeUtils.toMillis(LocalDateTime.of(2026, 9, 3, 10, 0), zone)
        val result = DaySplitter.split(listOf("x"), LocalDate.of(2026, 9, 3), LocalDate.of(2026, 9, 3), zone) { t to t }
        assertEquals(1, result[LocalDate.of(2026, 9, 3)]!!.size)
        val outside = DaySplitter.split(listOf("x"), LocalDate.of(2026, 9, 4), LocalDate.of(2026, 9, 5), zone) { t to t }
        assertTrue(outside.isEmpty())
    }

    @Test
    fun csvRoundTrip() {
        val rows = listOf(
            listOf("a", "b,c", "say \"hi\""),
            listOf("multi\nline", "", "plain"),
        )
        assertEquals(rows, Csv.parse(Csv.write(rows)))
    }

    @Test
    fun csvParsesBomAndMissingTrailingNewline() {
        assertEquals(listOf(listOf("x", "y"), listOf("1", "2")), Csv.parse("﻿x,y\n1,2"))
    }

    @Test
    fun placeNamingPrefersBuildingInsideCampus() {
        val campus = PlaceCandidate("City University", "university", 0.0, 0.0, 200.0, true, 500_000.0, PlaceCandidate.Kind.POI)
        val building = PlaceCandidate("Library", "university", 0.0, 0.0, 10.0, true, 2_000.0, PlaceCandidate.Kind.BUILDING)
        val cafe = PlaceCandidate("Cafe Nero", "cafe", 0.0, 0.0, 15.0, false, null, PlaceCandidate.Kind.POI)
        assertEquals("Library, City University" to "university", PlaceNaming.pickBestName(listOf(campus, building, cafe)))
        assertEquals("City University" to "university", PlaceNaming.pickBestName(listOf(campus, cafe)))
        assertEquals("Cafe Nero" to "cafe", PlaceNaming.pickBestName(listOf(cafe)))
        assertNull(PlaceNaming.pickBestName(listOf(cafe.copy(distanceMeters = 80.0))))
    }
}
