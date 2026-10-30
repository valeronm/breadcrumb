package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.domain.StayDeriver.Gap
import io.github.valeronm.breadcrumb.domain.StayDeriver.Stay
import io.github.valeronm.breadcrumb.domain.StayDeriver.TrackEnd
import io.github.valeronm.breadcrumb.domain.atlas.CityAtlas
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId

class HistoryClocksTest {

    private val east = ZoneId.of("Etc/GMT-3")
    private val west = ZoneId.of("Etc/GMT+5")
    private val device = ZoneId.of("Etc/GMT-9")

    private val home = at(0.0)
    private val away = at(50_000.0)
    private val roadside = at(100_000.0)

    private fun city(zone: String) =
        CityAtlas.City(name = "Town", country = "XX", zoneId = zone, population = 1_000, distanceM = 0.0, row = 0)

    private fun clustersOver(vararg endpoints: Coordinate, seeds: List<PlaceClusterer.Seed> = emptyList()) =
        PlaceClusterer.cluster(endpoints.toList(), distance = flatDistance, seeds = seeds)

    private fun clocks(
        clusters: List<PlaceClusterer.Cluster>,
        cities: Map<Coordinate, CityAtlas.City>,
        tracks: List<TrackEnd> = emptyList(),
        fallback: () -> ZoneId = { device },
    ) = HistoryClocks(StayDeriver.Derivation(emptyList(), clusters), cities, tracks, fallback)

    private var nextId = 0L

    private fun track(start: Coordinate?, end: Coordinate?) = TrackEnd(++nextId, 0L, 1L, start, end)

    @Test fun `a stay reads both ends on its cluster's clock`() {
        val clusters = clustersOver(home, home)
        val clocks = clocks(clusters, mapOf(clusters[0].atlasPoint to city(east.id)))

        assertEquals(Clocks(east, east), clocks.ofInterval(Stay(0L, 1L, afterTrackId = 1L, clusterId = 0)))
    }

    @Test fun `a gap reads each end on its own cluster's clock`() {
        val clusters = clustersOver(home, away)
        val clocks = clocks(clusters, mapOf(clusters[0].atlasPoint to city(east.id), clusters[1].atlasPoint to city(west.id)))

        val gap = Gap(0L, 1L, afterTrackId = 1L, from = Gap.Side(home, 0), to = Gap.Side(away, 1))

        assertEquals(Clocks(east, west), clocks.ofInterval(gap))
    }

    @Test fun `a gap end with no fix reads the device clock`() {
        val clusters = clustersOver(home)
        val clocks = clocks(clusters, mapOf(clusters[0].atlasPoint to city(east.id)))

        val gap = Gap(0L, 1L, afterTrackId = 1L, from = Gap.Side(home, 0), to = null)

        assertEquals(Clocks(east, device), clocks.ofInterval(gap))
    }

    @Test fun `a track reads each end on its cluster's clock`() {
        val crossing = track(home, away)
        val clusters = clustersOver(home, away)
        val clocks = clocks(
            clusters,
            mapOf(clusters[0].atlasPoint to city(east.id), clusters[1].atlasPoint to city(west.id)),
            listOf(crossing),
        )

        assertEquals(Clocks(east, west), clocks.ofTrack(crossing.trackId))
    }

    @Test fun `a track end in a cluster reads the cluster's clock, not its own spot's`() {
        val nearHome = at(100.0)
        val arrival = track(away, nearHome)
        val clusters = clustersOver(home, nearHome)
        val clocks = clocks(
            clusters,
            mapOf(clusters[0].atlasPoint to city(east.id), nearHome to city(west.id)),
            listOf(arrival),
        )

        assertEquals(1, clusters.size)
        assertEquals(east, clocks.ofTrack(arrival.trackId).end)
    }

    @Test fun `the atlas looked up at the atlas points places every track end`() {
        val outbound = track(home, roadside)
        val inbound = track(away, home)
        val tracks = listOf(outbound, inbound)
        val derivation = StayDeriver.Derivation(emptyList(), clustersOver(home, home))
        val atlas = mapOf(home to east, roadside to west, away to west)
        val cities = atlasPoints(derivation, tracks).associateWith { city(atlas.getValue(it).id) }
        val clocks = HistoryClocks(derivation, cities, tracks) { device }

        assertEquals(Clocks(east, west), clocks.ofTrack(outbound.trackId))
        assertEquals(Clocks(west, east), clocks.ofTrack(inbound.trackId))
    }

    @Test fun `a track end nothing places reads the device clock`() {
        val offTheAtlas = track(home, roadside)
        val noEnd = track(home, null)
        val clusters = clustersOver(home)
        val clocks = clocks(clusters, mapOf(clusters[0].atlasPoint to city(east.id)), listOf(offTheAtlas, noEnd))

        assertEquals(Clocks(east, device), clocks.ofTrack(offTheAtlas.trackId))
        assertEquals(Clocks(east, device), clocks.ofTrack(noEnd.trackId))
    }

    @Test fun `a zone this device cannot resolve reads the device clock`() {
        val clusters = clustersOver(home)
        val clocks = clocks(clusters, mapOf(clusters[0].atlasPoint to city("Europe/Atlantis")))

        assertEquals(device, clocks.ofCluster(0))
    }

    @Test fun `a place reads the clock where its visits landed, not where its pin was dropped`() {
        val pin = home
        val visits = at(300.0)
        val clusters = clustersOver(visits, visits, seeds = listOf(PlaceClusterer.Seed(pin, 400.0, placeId = 1L, named = true)))
        val clocks = clocks(clusters, mapOf(pin to city(west.id), clusters[0].atlasPoint to city(east.id)))

        assertEquals(visits, clusters[0].atlasPoint)
        assertEquals(east, clocks.ofCluster(0))
    }

    @Test fun `a clock nothing places is the device's when first asked for, and stays it`() {
        var deviceZone = east
        val unplaced = track(home, roadside)
        val clocks = clocks(clustersOver(home), emptyMap(), listOf(unplaced)) { deviceZone }

        deviceZone = west
        assertEquals(west, clocks.ofCluster(0))
        assertEquals(Clocks(west, west), clocks.ofTrack(unplaced.trackId))

        deviceZone = device
        assertEquals(west, clocks.ofCluster(0))
        assertEquals(Clocks(west, west), clocks.ofTrack(unplaced.trackId))
    }

    @Test fun `a cluster or a track the derivation does not hold reads the device clock on every call`() {
        var deviceZone = east
        val clusters = clustersOver(home)
        val clocks = clocks(clusters, mapOf(clusters[0].atlasPoint to city(device.id))) { deviceZone }

        assertEquals(east, clocks.ofCluster(null))
        deviceZone = west
        assertEquals(west, clocks.ofCluster(null))
        assertEquals(west, clocks.ofCluster(1))
        assertEquals(Clocks.both(west), clocks.ofTrack(-1L))
    }

    @Test fun `an atlas zone id parses where this device knows it`() {
        assertEquals(east, zoneOrNull(east.id))
        assertNull(zoneOrNull("Europe/Atlantis"))
        assertNull(zoneOrNull(null))
    }
}
