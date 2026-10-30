package io.github.valeronm.breadcrumb.domain.atlas

import java.time.ZoneId

/** A single spot's city, judged against every town the atlas holds. */
class SpotCity(
    override val city: AtlasCity,
    val zone: ZoneId?,
    override val sharesName: Boolean,
) : ListedCity {
    companion object {
        /** [city] must be one [atlas] named, since its row is read there. */
        fun of(city: CityAtlas.City, atlas: CityAtlas): SpotCity =
            SpotCity(AtlasCity.of(city), city.zone, atlas.hasNamesake(city.row))
    }
}
