package io.github.valeronm.breadcrumb.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Every country and city the whole history has been to, each with the number of days it was there,
 * most days first.
 *
 * A stay counts for the country its city lies in, and for the city only where
 * [TravelNaming.ClusterNames.visitsCity] allows; a track counts only when it begins and ends in the
 * same visited city. A day is a date on the clock of the cluster the time was spent at. Ties keep the
 * order the history first reached them in.
 */
object VisitedWorld {

    /** Judged against the other cities the history has been to. */
    data class City(
        override val city: TravelNaming.SpentCity,
        val days: Int,
        override val sharesName: Boolean,
    ) : TravelNaming.ListedCity

    /** [days] is not the sum of the cities' days: a day in two cities is one day here, and a stay
     *  that visits no city still counts one. */
    data class Country(val code: String, val days: Int, val cities: List<City>)

    fun of(
        timeline: TravelDeriver.Timeline,
        names: TravelNaming.ClusterNames,
        zoneOf: (clusterId: Int) -> ZoneId,
        nowMs: Long,
    ): List<Country> {
        val countryDays = LinkedHashMap<String, MutableSet<LocalDate>>()
        val cityDays = LinkedHashMap<TravelNaming.SpentCity, MutableSet<LocalDate>>()
        for (credit in creditsOf(timeline, names, nowMs).sortedBy { it.start }) {
            val dates = datesOf(credit.start, credit.end, zoneOf(credit.clusterId))
            countryDays.getOrPut(credit.country, ::HashSet).addAll(dates)
            credit.city?.let { cityDays.getOrPut(it, ::HashSet).addAll(dates) }
        }
        val shared = TravelNaming.namesakes(cityDays.keys)
        val citiesByCountry = cityDays.entries.groupBy({ it.key.country }) { (city, dates) ->
            City(city, dates.size, city in shared)
        }
        return countryDays.map { (code, dates) ->
            Country(code, dates.size, citiesByCountry[code].orEmpty().sortedByDescending { it.days })
        }.sortedByDescending { it.days }
    }

    private class Credit(
        val clusterId: Int,
        val country: String,
        val city: TravelNaming.SpentCity?,
        val start: Long,
        val end: Long,
    )

    private fun creditsOf(timeline: TravelDeriver.Timeline, names: TravelNaming.ClusterNames, nowMs: Long) =
        buildList {
            val derivation = timeline.derivation
            for (stay in derivation.intervals) {
                if (stay !is StayDeriver.Stay) continue
                val cluster = derivation.clusters.getOrNull(stay.clusterId) ?: continue
                val city = names.cityOf(cluster) ?: continue
                val visited = if (names.visitsCity(cluster)) TravelNaming.SpentCity.of(city) else null
                add(Credit(stay.clusterId, city.country, visited, stay.start, stay.end ?: nowMs))
            }
            for (track in timeline.tracks) {
                val startId = names.visitedClusterAt(derivation, track.start) ?: continue
                val endId = names.visitedClusterAt(derivation, track.end) ?: continue
                val city = names.cityOf(derivation.clusters[startId])?.let(TravelNaming.SpentCity::of) ?: continue
                if (names.cityOf(derivation.clusters[endId])?.let(TravelNaming.SpentCity::of) != city) continue
                add(Credit(startId, city.country, city, track.startedAt, track.endedAt))
            }
        }

    /** An interval ending at midnight stops the day before. */
    private fun datesOf(start: Long, end: Long, zone: ZoneId): List<LocalDate> {
        val first = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
        val last = Instant.ofEpochMilli(maxOf(start, end - 1)).atZone(zone).toLocalDate()
        return generateSequence(first) { it.plusDays(1) }.takeWhile { !it.isAfter(last) }.toList()
    }
}
