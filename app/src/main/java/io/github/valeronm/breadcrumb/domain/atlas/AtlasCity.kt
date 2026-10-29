package io.github.valeronm.breadcrumb.domain.atlas

/** A city as the atlas row naming it, which keeps two towns sharing a name from summing into one. */
data class AtlasCity(val row: Int, val name: String, val country: String, val region: String? = null) {
    companion object {
        fun of(city: CityAtlas.City) = AtlasCity(city.row, city.name, city.country, city.region)
    }
}
