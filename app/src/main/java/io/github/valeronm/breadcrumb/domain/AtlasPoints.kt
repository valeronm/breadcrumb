package io.github.valeronm.breadcrumb.domain

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
