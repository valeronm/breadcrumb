package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.Place
import io.github.valeronm.breadcrumb.domain.atlas.atlasOf
import io.github.valeronm.breadcrumb.domain.atlas.testCity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class HistoryReadingTest {

    private val west = ZoneId.of("Etc/GMT+5")
    private val east = ZoneId.of("Etc/GMT-3")
    private val far = ZoneId.of("Etc/GMT+1")
    private val device = ZoneId.of("Etc/GMT-9")

    private val cafe = Place(
        id = 1L,
        label = "Cafe",
        lat = ORIGIN_LAT,
        lon = lonAt(CAFE_AT),
        createdAt = 0L,
        radiusM = PlaceClusterer.DEFAULT_RADIUS_M,
        source = PlaceOrigin.MANUAL.code,
    )

    private val atlas = atlasOf(
        testCity("West", HOME, zone = west.id),
        testCity("East", CAFE_AT, zone = east.id),
        testCity("Far", TOWN_AT, zone = far.id),
    )

    private fun end(id: Long, startMin: Long, endMin: Long, from: Double, to: Double) =
        StayDeriver.TrackEnd(id, startMin * MIN, endMin * MIN, at(from), at(to))

    private val out = end(1, 0, 10, HOME, CAFE_AT)
    private val back = end(2, 130, 140, CAFE_AT, HOME)
    private val away = end(3, 260, 300, TOWN_AT, TOWN_END_AT)

    private fun history(vararg tracks: StayDeriver.TrackEnd): DerivedHistory {
        val ends = tracks.toList()
        val derivation = StayDeriver.derive(
            ends,
            distance = flatDistance,
            placePins = PlaceClusterer.seedsOf(listOf(cafe)),
        )
        val cities = atlasPoints(derivation, ends).associateWith { atlas.naming(it.lat, it.lon, flatDistance)!! }
        return DerivedHistory(derivation, listOf(cafe), now = 600 * MIN, ends, AtlasCities(cities)) { device }
    }

    private fun readingOf(vararg tracks: StayDeriver.TrackEnd): HistoryReading {
        val summaries = tracks.map { trackSummary(it.trackId, ActivityType.WALKING.name, it.startedAt, it.endedAt) }
            .sortedByDescending { it.startedAt }
        return HistoryReading(summaries, history(*tracks))
    }

    private val outings = readingOf(out, back, away)

    private val outingsHistory = history(out, back, away)

    private inline fun <reified T : TimelineItem> HistoryReading.rows() = timelineRows.filterIsInstance<T>()

    @Test fun `a gap row carries its sides' places and each end's clock`() {
        val resolved = outings.gaps.byAnchor.getValue(back.trackId)
        val halves = outings.rows<TimelineItem.GapItem>()

        assertNotNull(resolved.fromPlace)
        assertNotNull(resolved.toPlace)
        assertEquals(setOf(back.trackId), halves.map { it.gap.afterTrackId }.toSet())
        assertEquals(west, halves.single { it.holdsDeparture }.departureZone)
        assertEquals(far, halves.single { it.holdsArrival }.arrivalZone)
        halves.forEach {
            assertEquals(resolved.fromPlace, it.fromPlace)
            assertEquals(resolved.toPlace, it.toPlace)
        }
        assertEquals(west, resolved.item().departureZone)
        assertEquals(far, resolved.item().arrivalZone)
    }

    @Test fun `a stay row carries its place, and a long stay's swipe hides it`() {
        val stays = outings.rows<TimelineItem.StayItem>().filter { it.stay.afterTrackId == out.trackId }

        assertTrue(stays.isNotEmpty())
        stays.forEach {
            assertEquals("Cafe", it.place?.place?.label)
            assertEquals(StaySwipe.Hide(HiddenStay(out.trackId, back.trackId)), it.swipe)
        }
    }

    @Test fun `a brief stop between two walks offers their merge`() {
        val onward = end(2, 13, 23, CAFE_AT, ONWARD_AT)
        val reading = readingOf(out, onward)
        val stay = reading.rows<TimelineItem.StayItem>().single { it.stay.afterTrackId == out.trackId }

        assertEquals(StaySwipe.Merge(TrackMerge.Plan(out.trackId, onward.trackId)), stay.swipe)
        assertEquals(setOf(out.trackId), reading.mergeableAnchors)
    }

    @Test fun `a trip row carries its part's clocks`() {
        val row = outings.rows<TimelineItem.TrackItem>().single { it.trip.main.id == out.trackId }

        assertEquals(TripClocks(listOf(Clocks(west, east))), row.clocks)
    }

    @Test fun `the gaps hold the tracks either side of each gap and no others`() {
        val gaps = outings.gaps

        assertEquals(setOf(back.trackId), gaps.neighbors.keys)
        val sides = gaps.neighbors.getValue(back.trackId)
        assertEquals(listOf(back.trackId, away.trackId), listOf(sides.before.id, sides.after.id))
    }

    @Test fun `a short gap between two walks offers their merge on its resolved form and its rows`() {
        val next = end(2, 13, 23, SPOT_AT, SPOT_END_AT)
        val reading = readingOf(out, next)
        val resolved = reading.gaps.byAnchor.getValue(out.trackId)
        val halves = reading.rows<TimelineItem.GapItem>()
        val merge = TrackMerge.Plan(out.trackId, next.trackId)

        assertEquals(merge, resolved.merge)
        assertTrue(halves.isNotEmpty())
        halves.forEach { assertEquals(merge, it.merge) }
    }

    @Test fun `mapping the gaps' places changes the sides it renames and keeps the keys`() {
        val gaps = outings.gaps
        val resolved = gaps.byAnchor.getValue(back.trackId)
        val departure = resolved.fromPlace!!
        val home = cafe.copy(id = 2L, label = "Home")

        val renamed = gaps.mapPlaces { if (it.key == departure.key) it.withPlace(home) else it }

        val after = renamed.byAnchor.getValue(back.trackId)
        assertEquals("Home", after.fromPlace?.place?.label)
        assertSame(resolved.toPlace, after.toPlace)
        assertEquals(gaps.byAnchor.keys, renamed.byAnchor.keys)
        assertEquals(gaps.neighbors.keys, renamed.neighbors.keys)
    }

    @Test fun `mapping the gaps' places to themselves gives back the same gaps`() {
        val gaps = outings.gaps
        val same = gaps.mapPlaces { it }

        assertTrue(gaps.oldestFirst.isNotEmpty())
        assertEquals(gaps.oldestFirst.size, same.oldestFirst.size)
        gaps.oldestFirst.zip(same.oldestFirst).forEach { (before, after) -> assertSame(before, after) }
    }

    @Test fun `a timeline's months are summed once, on first read`() {
        var clockReads = 0
        val now = {
            clockReads++
            600 * MIN
        }
        val timeline = ResolvedTimeline(outings.timelineRows, now) { device }

        assertEquals(0, clockReads)
        assertTrue(timeline.months.isNotEmpty())
        assertSame(timeline.months, timeline.months)
        assertEquals(1, clockReads)
    }

    @Test fun `place summaries name each cluster's city`() {
        val summaries = outingsHistory.placeSummaries()

        assertEquals(east, summaries.single { it.place?.label == "Cafe" }.locality?.zone)
    }

    @Test fun `the world counts the countries the cities are in`() {
        assertEquals(listOf("XX"), outingsHistory.world().map { it.code })
    }

    private companion object {
        const val HOME = 0.0
        const val CAFE_AT = 500.0
        const val ONWARD_AT = 1_000.0
        const val SPOT_AT = 3_000.0
        const val SPOT_END_AT = 3_500.0
        const val TOWN_AT = 5_000.0
        const val TOWN_END_AT = 5_500.0
    }
}
