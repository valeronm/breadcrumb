package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.domain.atlas.CityAtlas
import java.time.ZoneId

/**
 * A track end in a cluster runs on that cluster's clock, so it agrees with the stay beside it.
 * [cities] is the atlas looked up at [atlasPoints].
 *
 * Cluster and track clocks are worked out once per instance, reading [fallback] then for any that
 * nothing places; a cluster id or a track this derivation does not hold reads it on every call.
 */
class HistoryClocks(
    private val derivation: StayDeriver.Derivation,
    private val cities: Map<Coordinate, CityAtlas.City>,
    tracks: List<StayDeriver.TrackEnd>,
    private val fallback: () -> ZoneId,
) {
    /** `ZoneId.of` parses and allocates on every call. */
    private val byCluster: List<ZoneId> by lazy {
        derivation.clusters.map { zoneOf(cities[it.atlasPoint]) }
    }

    private val byTrack: Map<Long, Clocks> by lazy {
        fun endZone(end: Coordinate?) = end?.let(::zoneAt) ?: fallback()
        tracks.associate { it.trackId to Clocks(endZone(it.start), endZone(it.end)) }
    }

    fun ofCluster(id: Int?): ZoneId = id?.let(byCluster::getOrNull) ?: fallback()

    fun ofTrack(trackId: Long): Clocks = byTrack[trackId] ?: Clocks.both(fallback())

    fun ofInterval(interval: StayDeriver.Interval): Clocks = when (interval) {
        is StayDeriver.Stay -> Clocks.both(ofCluster(interval.clusterId))
        is StayDeriver.Gap -> Clocks(ofCluster(interval.from?.clusterId), ofCluster(interval.to?.clusterId))
    }

    private fun zoneAt(end: Coordinate): ZoneId =
        derivation.clusterOfEndpoint[end]?.let(::ofCluster) ?: zoneOf(cities[end])

    private fun zoneOf(city: CityAtlas.City?): ZoneId = zoneOrNull(city?.zoneId) ?: fallback()
}

/**
 * Null where this Android's tz database cannot resolve [zoneId]. The atlas ships its ids in a
 * checked-in asset and the tz database ships with Android, so a zone renamed upstream
 * (`Europe/Kiev` → `Europe/Kyiv`) or dropped leaves the asset naming one the device does not know.
 */
fun zoneOrNull(zoneId: String?): ZoneId? = zoneId?.let { runCatching { ZoneId.of(it) }.getOrNull() }
