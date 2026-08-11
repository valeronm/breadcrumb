package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.Place
import io.github.valeronm.breadcrumb.domain.StayDeriver.TrackEnd
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a journey is named after, and what it counts towards — the half of naming that needs a
 * gazetteer and a places table, as against [TravelNamingTest]'s ranking of a map someone else built.
 *
 * Fixtures put three towns east of the neutral origin, far enough apart that each names itself, and
 * drive a journey over them by hand: a [TravelDeriver.Travel] is built directly rather than derived,
 * so a failure here is about naming rather than about which nights were away.
 */
class TravelSummaryTest {

    private val hometown = testCity("Hometown", 0.0, pop = 40)
    private val seaside = testCity("Seaside", 60_000.0, pop = 30)
    private val hillTown = testCity("Hill town", 120_000.0, pop = 25, country = "YY")
    private val atlas = atlasOf(hometown, seaside, hillTown)

    private val hour = 3_600_000L
    private fun at(city: TestCity) = Coordinate(city.lat, city.lon)

    private var nextId = 0L

    private fun place(label: String, city: TestCity, category: PlaceCategory?) = Place(
        id = ++nextId, label = label, lat = city.lat, lon = city.lon,
        createdAt = 0L, radiusM = 400.0, category = category?.code,
    )

    private fun track(from: TestCity, to: TestCity, startedAt: Long, endedAt: Long) =
        TrackEnd(++nextId, startedAt, endedAt, at(from), at(to))

    /** A journey over [clusterStayMs], running for a day from an arbitrary epoch. */
    private fun travel(clusterStayMs: Map<Int, Long>, span: LongRange = 0L..(24 * hour)) =
        TravelDeriver.Travel(
            firstNight = java.time.LocalDate.of(2024, 5, 2),
            lastNight = java.time.LocalDate.of(2024, 5, 2),
            firstNightAt = span.first,
            lastNightAt = span.last,
            leftHomeAt = null,
            reachedHomeAt = null,
            windowStart = span.first,
            windowEnd = span.last,
            clusterStayMs = clusterStayMs,
        )

    private fun summarize(
        travel: TravelDeriver.Travel,
        clusters: List<Coordinate>,
        places: List<Place> = emptyList(),
        tracks: List<TrackEnd> = emptyList(),
    ): TravelNaming.Summary {
        val derivation = StayDeriver.Derivation(
            emptyList(),
            PlaceClusterer.cluster(clusters, distance = flatDistance, seeds = PlaceClusterer.seedsOf(places)),
        )
        return TravelNaming.summarize(
            listOf(travel),
            TravelDeriver.Timeline(derivation, tracks),
            TravelNaming.Gazetteer(atlas, places, flatDistance),
        ).single()
    }

    @Test fun `a journey is named after the cities its clusters sit in`() {
        val summary = summarize(
            travel(mapOf(0 to 20 * hour, 1 to 5 * hour)),
            listOf(at(seaside), at(hillTown)),
        )

        assertEquals(listOf("Seaside", "Hill town"), summary.destinations)
        assertEquals(setOf("Seaside", "Hill town"), summary.cities)
        assertEquals(setOf("XX", "YY"), summary.countries)
    }

    @Test fun `a person's place keeps its own name, a hotel does not`() {
        val places = listOf(
            place("Mum's", hillTown, PlaceCategory.FRIENDS_FAMILY),
            place("Hotel Grand", seaside, PlaceCategory.TRAVEL),
        )
        // Seeded clusters come first and in pin order, so cluster 0 is Mum's and 1 the hotel.
        val summary = summarize(
            travel(mapOf(0 to 5 * hour, 1 to 20 * hour)),
            listOf(at(hillTown), at(seaside)),
            places,
        )

        assertEquals(listOf("Seaside", "Mum's"), summary.destinations)
        // The city is still the city, whatever the journey chose to call the stop in it.
        assertEquals(setOf("Seaside", "Hill town"), summary.cities)
    }

    @Test fun `the road is not a place on it`() {
        // A three-hour stop at a service area would clear the floor and name the journey after a
        // town nobody saw; it counts for nothing, though the country it sits in still counts.
        val places = listOf(place("Motorway services", hillTown, PlaceCategory.SERVICE_AREA))
        val summary = summarize(
            travel(mapOf(0 to 3 * hour, 1 to 20 * hour)),
            listOf(at(hillTown), at(seaside)),
            places,
        )

        assertEquals(listOf("Seaside"), summary.destinations)
        assertEquals(setOf("Seaside"), summary.cities)
        assertEquals(setOf("XX", "YY"), summary.countries)
    }

    @Test fun `a day walking a city counts as time in it`() {
        // The shape of on-foot recording: minutes of stays, hours of tracks, all inside one city.
        val walking = (0 until 6).map { i ->
            track(seaside, seaside, startedAt = i * 3 * hour, endedAt = i * 3 * hour + 2 * hour)
        }
        val summary = summarize(
            travel(mapOf(0 to 10 * 60_000L)), // ten minutes of pauses
            listOf(at(seaside)),
            tracks = walking,
        )

        assertEquals(listOf("Seaside"), summary.destinations)
    }

    @Test fun `the drive between two places counts for neither`() {
        val summary = summarize(
            travel(mapOf(0 to 20 * 60_000L, 1 to 20 * 60_000L)),
            listOf(at(seaside), at(hillTown)),
            tracks = listOf(track(seaside, hillTown, startedAt = hour, endedAt = 5 * hour)),
        )

        // Four hours on the road, and neither end earns the two hours a name takes; the longest
        // stay names it rather than leaving the journey anonymous.
        assertEquals(1, summary.destinations.size)
    }

    @Test fun `tracks outside the journey's own window are not its time`() {
        val summary = summarize(
            travel(mapOf(0 to 10 * 60_000L), span = 0L..(6 * hour)),
            listOf(at(seaside)),
            tracks = listOf(track(seaside, seaside, startedAt = 20 * hour, endedAt = 28 * hour)),
        )

        // Eight hours in Seaside, all of it after the journey ended: the journey keeps only the
        // stay it actually held, which is under the floor and survives as the sole fallback.
        assertEquals(listOf("Seaside"), summary.destinations)
        assertEquals(10 * 60_000L, summary.travel.clusterStayMs.values.single())
    }
}
