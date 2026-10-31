package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.domain.atlas.CityAtlas

/** A place's pin says what the user calls a spot, not where on earth it is. */
val PlaceClusterer.Cluster.atlasPoint: Coordinate get() = centroid

/** A track end in no cluster has no cluster to take a city from. */
fun atlasPoints(derivation: StayDeriver.Derivation, tracks: List<StayDeriver.TrackEnd>): List<Coordinate> =
    derivation.clusters.map { it.atlasPoint } + unclusteredEnds(derivation, tracks)

private fun unclusteredEnds(derivation: StayDeriver.Derivation, tracks: List<StayDeriver.TrackEnd>): List<Coordinate> =
    buildList {
        for (track in tracks) {
            track.start?.takeUnless(derivation.clusterOfEndpoint::containsKey)?.let(::add)
            track.end?.takeUnless(derivation.clusterOfEndpoint::containsKey)?.let(::add)
        }
    }

/** Answers only the points [atlasPoints] lists for the derivation it was built from; any other reads as no city. */
class AtlasCities(private val cities: Map<Coordinate, CityAtlas.City?> = emptyMap()) {
    fun ofCluster(cluster: PlaceClusterer.Cluster): CityAtlas.City? = cities[cluster.atlasPoint]

    fun ofLooseEnd(end: Coordinate): CityAtlas.City? = cities[end]
}
