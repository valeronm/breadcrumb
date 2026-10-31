package io.github.valeronm.breadcrumb.history

import io.github.valeronm.breadcrumb.domain.AtlasCities
import io.github.valeronm.breadcrumb.domain.Coordinate
import io.github.valeronm.breadcrumb.domain.StayDeriver
import io.github.valeronm.breadcrumb.domain.atlas.CityAtlas
import io.github.valeronm.breadcrumb.domain.atlasPoints

/** Unsynchronized: a call must finish before the next begins. */
internal class CityCache(private val cityAt: CityLookup) {
    /**
     * Replaced each call rather than grown: every finished track moves a centroid, and the process
     * lives for weeks. A null value means the atlas reaches nothing there.
     *
     * Never written once stored, since every [AtlasCities] handed out reads it.
     */
    private var byPoint: Map<Coordinate, CityAtlas.City?> = emptyMap()

    private var forLanguage: String? = null

    fun citiesAt(
        derivation: StayDeriver.Derivation,
        tracks: List<StayDeriver.TrackEnd>,
        language: String,
    ): AtlasCities {
        val points = atlasPoints(derivation, tracks)
        val previous = if (language == forLanguage) byPoint else emptyMap()
        val resolved = HashMap<Coordinate, CityAtlas.City?>(points.size)
        for (at in points) {
            if (at in resolved) continue
            resolved[at] = if (previous.containsKey(at)) previous[at] else cityAt(at, language)
        }
        byPoint = resolved
        forLanguage = language
        return AtlasCities(resolved)
    }
}
