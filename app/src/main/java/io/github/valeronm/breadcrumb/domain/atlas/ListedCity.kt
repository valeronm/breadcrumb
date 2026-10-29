package io.github.valeronm.breadcrumb.domain.atlas

/**
 * A city whose [region] is set only where [sharesName]: where another city in its country goes by its
 * name, among the cities an implementer is judged against. Namesakes in one region, or in a region the
 * atlas has no name for, still read alike.
 */
interface ListedCity {
    val city: AtlasCity
    val sharesName: Boolean
    val name: String get() = city.name
    val region: String? get() = city.region.takeIf { sharesName }
}

/** The cities among [cities] whose name another of them in the same country carries. */
fun namesakes(cities: Collection<AtlasCity>): Set<AtlasCity> =
    cities.groupBy { it.country to it.name }.values.filter { it.size > 1 }.flatten().toSet()
