package io.github.valeronm.breadcrumb.domain

/** A single spot's city, judged against every town the atlas holds. */
class SpotCity(
    override val city: TravelNaming.SpentCity,
    val zoneId: String,
    override val sharesName: Boolean,
) : TravelNaming.ListedCity {
    companion object {
        /** [city] must be one [atlas] named, since its row is read there. */
        fun of(city: CityAtlas.City, atlas: CityAtlas): SpotCity =
            SpotCity(TravelNaming.SpentCity.of(city), city.zoneId, atlas.hasNamesake(city.row))
    }
}
