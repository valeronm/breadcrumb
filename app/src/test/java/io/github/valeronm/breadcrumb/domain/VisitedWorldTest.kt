package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.Place
import io.github.valeronm.breadcrumb.domain.StayDeriver.TrackEnd
import io.github.valeronm.breadcrumb.domain.VisitedWorld.City
import io.github.valeronm.breadcrumb.domain.VisitedWorld.Country
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class VisitedWorldTest {

    private val hometown = testCity("Hometown", 0.0, pop = 40)
    private val seaside = testCity("Seaside", 60_000.0, pop = 30)
    private val hillTown = testCity("Hill town", 120_000.0, pop = 25, country = "YY")
    private val eastPort = testCity("East port", 180_000.0, pop = 25, country = "ZZ", zone = "Etc/GMT-3")
    private val atlas = atlasOf(hometown, seaside, hillTown, eastPort)

    private val hour = 3_600_000L
    private val day = 24 * hour

    private val day1 = Instant.parse("2024-05-02T00:00:00Z").toEpochMilli()

    private fun at(city: TestCity) = Coordinate(city.lat, city.lon)

    private var nextId = 0L

    private fun place(city: TestCity, category: PlaceCategory) = Place(
        id = ++nextId, label = "${city.name} place", lat = city.lat, lon = city.lon,
        createdAt = 0L, radiusM = 400.0, category = category.code,
    )

    private fun stay(cluster: Int, start: Long, end: Long?) = StayDeriver.Stay(start, end, ++nextId, cluster)

    private fun track(from: TestCity, to: TestCity, startedAt: Long, endedAt: Long) =
        TrackEnd(++nextId, startedAt, endedAt, at(from), at(to))

    /** Clusters are made over [clusterAt] in order, so a stay's cluster index is its city's index there. */
    private fun world(
        clusterAt: List<TestCity>,
        stays: List<StayDeriver.Stay>,
        tracks: List<TrackEnd> = emptyList(),
        places: List<Place> = emptyList(),
        nowMs: Long = day1 + 30 * day,
    ): List<Country> {
        val clusters = PlaceClusterer.cluster(clusterAt.map(::at), distance = flatDistance, seeds = PlaceClusterer.seedsOf(places))
        val names = TravelNaming.ClusterNames(places) { atlas.naming(it.centroid.lat, it.centroid.lon, flatDistance) }
        return VisitedWorld.of(
            TravelDeriver.Timeline(StayDeriver.Derivation(stays, clusters), tracks),
            names,
            { id -> ZoneId.of(names.cityOf(clusters[id])!!.zoneId) },
            nowMs,
        )
    }

    @Test fun `a stay counts each day it covers for its city and its country`() {
        val world = world(
            listOf(seaside),
            listOf(stay(0, day1 + 22 * hour, day1 + day + 8 * hour)),
        )

        assertEquals(listOf(Country("XX", 2, listOf(City("Seaside", 2)))), world)
    }

    @Test fun `a day is counted once however many stays it holds`() {
        val world = world(
            listOf(hometown, seaside),
            listOf(
                stay(0, day1 + 8 * hour, day1 + 9 * hour),
                stay(1, day1 + 12 * hour, day1 + 13 * hour),
                stay(0, day1 + 18 * hour, day1 + 19 * hour),
            ),
        )

        assertEquals(
            listOf(Country("XX", 1, listOf(City("Hometown", 1), City("Seaside", 1)))),
            world,
        )
    }

    @Test fun `a fuel stop counts its country but not its town`() {
        val places = listOf(place(hillTown, PlaceCategory.GAS_STATION))
        val world = world(
            listOf(hillTown, seaside),
            listOf(
                stay(0, day1 + 10 * hour, day1 + 10 * hour + 600_000L),
                stay(1, day1 + day + 10 * hour, day1 + 2 * day + 12 * hour),
            ),
            places = places,
        )

        assertEquals(
            listOf(
                Country("XX", 2, listOf(City("Seaside", 2))),
                Country("YY", 1, emptyList()),
            ),
            world,
        )
    }

    @Test fun `a walk that starts and ends in one city counts the day for it`() {
        val world = world(
            listOf(seaside),
            stays = emptyList(),
            tracks = listOf(track(seaside, seaside, day1 + 10 * hour, day1 + 12 * hour)),
        )

        assertEquals(listOf(Country("XX", 1, listOf(City("Seaside", 1)))), world)
    }

    @Test fun `the drive between two cities counts for neither`() {
        val world = world(
            listOf(seaside, hillTown),
            stays = emptyList(),
            tracks = listOf(track(seaside, hillTown, day1 + 10 * hour, day1 + 12 * hour)),
        )

        assertEquals(emptyList<Country>(), world)
    }

    @Test fun `days are counted on the city's own clock`() {
        val world = world(
            listOf(eastPort),
            listOf(stay(0, day1 + 20 * hour, day1 + 23 * hour)),
        )

        assertEquals(listOf(Country("ZZ", 2, listOf(City("East port", 2)))), world)
    }

    @Test fun `the stay still open runs to now`() {
        val world = world(
            listOf(hometown),
            listOf(stay(0, day1 + 12 * hour, null)),
            nowMs = day1 + 2 * day + hour,
        )

        assertEquals(listOf(Country("XX", 3, listOf(City("Hometown", 3)))), world)
    }

    @Test fun `countries and their cities run from most days to fewest`() {
        val world = world(
            listOf(hometown, seaside, hillTown),
            listOf(
                stay(2, day1 + 10 * hour, day1 + 11 * hour),
                stay(1, day1 + day + 10 * hour, day1 + day + 11 * hour),
                stay(0, day1 + 2 * day + 10 * hour, day1 + 4 * day + 11 * hour),
            ),
        )

        assertEquals(
            listOf(
                Country("XX", 4, listOf(City("Hometown", 3), City("Seaside", 1))),
                Country("YY", 1, listOf(City("Hill town", 1))),
            ),
            world,
        )
    }

    @Test fun `a stay ending at midnight does not count the day after`() {
        val world = world(
            listOf(seaside),
            listOf(stay(0, day1 + 20 * hour, day1 + day)),
        )

        assertEquals(listOf(Country("XX", 1, listOf(City("Seaside", 1)))), world)
    }
}
