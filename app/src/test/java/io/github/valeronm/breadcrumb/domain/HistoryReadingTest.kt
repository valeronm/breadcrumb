package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.Place
import io.github.valeronm.breadcrumb.domain.atlas.atlasOf
import io.github.valeronm.breadcrumb.domain.atlas.testCity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import kotlin.math.hypot

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

    private fun history(vararg tracks: StayDeriver.TrackEnd, withTail: Boolean = false, places: List<Place> = listOf(cafe)): DerivedHistory {
        val ends = tracks.toList()
        val derived = StayDeriver.derive(
            ends,
            distance = flatDistance,
            placePins = PlaceClusterer.seedsOf(places),
        )
        val derivation = if (withTail) derived.withTailAfter(ends.last()) else derived
        val cities = atlasPoints(derivation, ends).associateWith { atlas.naming(it.lat, it.lon, flatDistance)!! }
        return DerivedHistory(derivation, places, now = 600 * MIN, ends, AtlasCities(cities)) { device }
    }

    private fun StayDeriver.Derivation.withTailAfter(last: StayDeriver.TrackEnd): StayDeriver.Derivation {
        val anchor = StayDeriver.TailAnchor(last.trackId, last.endedAt, clusterOfEndpoint.getValue(checkNotNull(last.end)))
        val tail = checkNotNull(StayDeriver.tail(anchor, disarmedSince = null, nowMs = 600 * MIN, activeStartedAt = null))
        return StayDeriver.Derivation(intervals + tail, clusters)
    }

    private fun readingOf(vararg tracks: StayDeriver.TrackEnd, withTail: Boolean = false, places: List<Place> = listOf(cafe)): HistoryReading {
        val summaries = tracks.map { trackSummary(it.trackId, ActivityType.WALKING.name, it.startedAt, it.endedAt) }
            .sortedByDescending { it.startedAt }
        return HistoryReading(summaries, history(*tracks, withTail = withTail, places = places))
    }

    private val outings = readingOf(out, back, away)

    private val ongoing = readingOf(out, back, away, withTail = true)

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

    @Test fun `a stay reading carries the merge its swipe offers, and a long stay's none`() {
        val onward = end(2, 13, 23, CAFE_AT, ONWARD_AT)

        assertEquals(TrackMerge.Plan(out.trackId, onward.trackId), checkNotNull(readingOf(out, onward).stay(out.trackId)).merge)
        assertNull(checkNotNull(outings.stay(out.trackId)).merge)
    }

    @Test fun `a stay reading carries its place, both facing ends and the track after`() {
        val resolved = checkNotNull(outings.stay(out.trackId))

        assertEquals("Cafe", resolved.place?.place?.label)
        assertEquals(back.trackId, resolved.afterId)
        assertEquals(out.end, resolved.arrival)
        assertEquals(back.start, resolved.departure)
    }

    @Test fun `the stay going on now has no track after and no departure`() {
        val resolved = checkNotNull(ongoing.stay(away.trackId))

        assertNull(resolved.stay.end)
        assertNull(resolved.afterId)
        assertEquals(away.end, resolved.arrival)
        assertNull(resolved.departure)
    }

    @Test fun `a stay reading at an unnamed spot offers every finished stay there, and not the one going on now`() {
        val again = end(3, 260, 270, HOME, CAFE_AT)
        val home = end(4, 390, 400, CAFE_AT, HOME)
        val reading = readingOf(out, back, again, home, withTail = true, places = listOf(cafe.copy(label = null)))

        assertEquals(
            listOf(HiddenStay(out.trackId, back.trackId), HiddenStay(again.trackId, home.trackId)),
            checkNotNull(reading.stay(out.trackId)).hideableHere,
        )
        assertEquals(listOf(HiddenStay(back.trackId, again.trackId)), checkNotNull(reading.stay(home.trackId)).hideableHere)
    }

    @Test fun `a stay reading at a named place carries every finished stay there too`() {
        val again = end(3, 260, 270, HOME, CAFE_AT)
        val home = end(4, 390, 400, CAFE_AT, HOME)
        val reading = readingOf(out, back, again, home)

        assertEquals(
            listOf(HiddenStay(out.trackId, back.trackId), HiddenStay(again.trackId, home.trackId)),
            checkNotNull(reading.stay(out.trackId)).hideableHere,
        )
    }

    private fun stayBetween(arrival: Coordinate?, departure: Coordinate?) = ResolvedStayInterval(
        stay = StayDeriver.Stay(0L, null, afterTrackId = 1, clusterId = 0),
        place = null,
        clocks = Clocks.both(west),
        hide = null,
        arrival = arrival,
        departure = departure,
        hideableHere = emptyList(),
        merge = null,
    )

    @Test fun `the middle of two ends is between them, of one end the end itself`() {
        val arrival = Coordinate(0.0, 0.0)
        assertEquals(Coordinate(200.0, 0.0), stayBetween(arrival, Coordinate(400.0, 0.0)).middle)
        assertEquals(arrival, stayBetween(arrival, null).middle)
        assertNull(stayBetween(null, null).middle)
    }

    private val flat = DistanceFn { aLat, aLon, bLat, bLon -> hypot(bLat - aLat, bLon - aLon) }

    private fun placedAt(place: Place?, centroid: Coordinate) =
        PlaceResolver.ResolvedStay(place, visitCount = 1, centroid = centroid, radiusM = PlaceClusterer.DEFAULT_RADIUS_M)

    @Test fun `a stay lies as far from its place as the nearer end is from the pin`() {
        val pinned = Place(id = 1, label = "Cafe", lat = 300.0, lon = 0.0, createdAt = 0L, radiusM = PlaceClusterer.DEFAULT_RADIUS_M)
        val stay = stayBetween(Coordinate(0.0, 0.0), Coordinate(400.0, 0.0))
            .copy(place = placedAt(pinned, centroid = Coordinate(0.0, 0.0)))

        assertEquals(100.0, stay.placeDistanceM(flat)!!, 0.0)
    }

    @Test fun `a stay at a cluster no place row names is measured to its centroid`() {
        val stay = stayBetween(Coordinate(0.0, 0.0), null).copy(place = placedAt(null, centroid = Coordinate(0.0, 12.0)))

        assertEquals(12.0, stay.placeDistanceM(flat)!!, 0.0)
    }

    @Test fun `a stay without a place or without ends has no distance to one`() {
        assertNull(stayBetween(Coordinate(0.0, 0.0), null).placeDistanceM(flat))
        assertNull(stayBetween(null, null).copy(place = placedAt(null, Coordinate(0.0, 0.0))).placeDistanceM(flat))
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
