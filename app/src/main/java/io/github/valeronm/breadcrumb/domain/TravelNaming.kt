package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.Place

/**
 * Turns the places a journey was spent in into what to call it. Separate from [TravelDeriver]
 * because naming needs the gazetteer and the places table, and the rule about nights must know
 * neither; separate from the screen because which names survive is a decision, not a layout.
 *
 * **A journey through three cities is a journey through three cities.** Picking the one with the
 * most hours in it and calling that the destination reads as certainty the data does not have —
 * between near-equal stays the winner turns on an hour, and the other cities disappear from a
 * timeline that is supposed to be a record of where someone was.
 */
/**
 * What a journey is headed with: the places it was spent in, or — where nothing cleared the naming
 * floor — a count of nights for the host to word. [Destinations] carries proper nouns, which are the
 * same in every language; [NightsAway] carries a number, which is not.
 */
sealed interface TravelLabel {
    data class Destinations(val title: String) : TravelLabel

    data class NightsAway(val nights: Int) : TravelLabel
}

object TravelNaming {

    /** A journey with what to call it, and what it counted towards. */
    class Summary(
        val travel: TravelDeriver.Travel,
        /** Everywhere the journey was spent, most time first; empty when nothing can name it. */
        val destinations: List<String>,
        /**
         * Every city the journey touched, whatever it was called on the way — a place too brief to
         * headline a journey was still somewhere its traveller went, and a stop the user named
         * themselves still sits in a city. Counted, never displayed as the journey's name.
         */
        val cities: Set<String>,
        /**
         * ISO 3166-1 alpha-2 codes the journey touched. Taken from every cluster it holds, not only
         * the ones that earned a name — a country crossed is a country visited, whether or not the
         * stop there was long enough to be worth naming the trip after.
         */
        val countries: Set<String>,
    )

    /**
     * The gazetteer with a places table beside it, answering "what is this coordinate called" once
     * per coordinate. Every journey asks about the same hotels and the same track ends, and each
     * answer is several walks of a 160,000-row table; one of these serves a whole pass.
     *
     * Holds the [DistanceFn] because both questions it answers are geometric — this is the seam
     * through which the platform's own distance reaches an otherwise pure rule.
     */
    class Gazetteer(
        private val atlas: CityAtlas,
        private val places: List<Place>,
        private val distance: DistanceFn,
    ) {
        private val seeds = PlaceClusterer.seedsOf(places)
        private val cities = HashMap<StayDeriver.Endpoint, CityAtlas.City?>()
        private val pins = HashMap<StayDeriver.Endpoint, Place?>()

        fun cityAt(at: StayDeriver.Endpoint): CityAtlas.City? =
            cities.getOrPut(at) { atlas.naming(at.lat, at.lon, distance) }

        /** The named place whose capture area holds [at], if any. */
        fun pinAt(at: StayDeriver.Endpoint): Place? =
            pins.getOrPut(at) {
                PlaceClusterer.nearestSeedIndex(at.lat, at.lon, seeds, distance)?.let(places::getOrNull)
            }

        /** The place a cluster grew from, which is the pin that seeded it rather than a search. */
        fun placeOf(cluster: PlaceClusterer.Cluster): Place? =
            cluster.seedIndex?.let(places::getOrNull)
    }

    /** Names each of [travels] from the timeline it was derived from. */
    fun summarize(
        travels: List<TravelDeriver.Travel>,
        timeline: TravelDeriver.Timeline,
        gazetteer: Gazetteer,
    ): List<Summary> = travels.map { summarize(it, timeline, gazetteer) }

    /**
     * Everywhere one journey was spent, ranked — every place that earned a share of it, not the one
     * that happened to hold the most hours. Names are summed before ranking, so a city stayed in
     * twice is one place someone went rather than two smaller ones.
     */
    private fun summarize(
        travel: TravelDeriver.Travel,
        timeline: TravelDeriver.Timeline,
        gazetteer: Gazetteer,
    ): Summary {
        val timeByName = LinkedHashMap<String, Long>()
        val cities = LinkedHashSet<String>()
        val countries = LinkedHashSet<String>()
        for ((clusterId, ms) in travel.clusterStayMs) {
            val cluster = timeline.derivation.clusters.getOrNull(clusterId) ?: continue
            val at = cluster.endpointMean ?: cluster.anchor
            val city = gazetteer.cityAt(at)
            val place = gazetteer.placeOf(cluster)
            // A country crossed is a country visited even when the only stop in it was for fuel, so
            // countries are counted from every cluster; a city is not visited by refuelling in it.
            city?.let { countries += it.country }
            if (place?.placeCategory?.visited == false) continue
            city?.let { cities += it.name }
            val name = nameOf(place) { city?.name } ?: continue
            timeByName[name] = (timeByName[name] ?: 0L) + ms
        }
        addTimeMoving(travel, timeline.tracks, gazetteer, timeByName)
        return Summary(travel, ranked(timeByName), cities, countries)
    }

    /**
     * Credits a place with the tracks that **begin and end in it** — walking a city all day is time
     * spent in that city, and without it a place is only worth the pauses between its tracks.
     *
     * That distinction is the whole rule: a walk around a town starts and ends in the same place, so
     * it counts; the drive from one town to the next starts and ends in different ones and counts for
     * neither, which is right — nobody spent that hour anywhere.
     *
     * It matters most for the recording still to come. A day on foot is mostly tracks broken by
     * five-minute pauses, so measured by stays alone the city someone spent it in barely registers,
     * while the car park at either end holds minutes.
     */
    private fun addTimeMoving(
        travel: TravelDeriver.Travel,
        tracks: List<StayDeriver.TrackEnd>,
        gazetteer: Gazetteer,
        timeByName: MutableMap<String, Long>,
    ) {
        fun nameAt(at: StayDeriver.Endpoint?): String? {
            if (at == null) return null
            val place = gazetteer.pinAt(at)
            if (place?.placeCategory?.visited == false) return null
            return nameOf(place) { gazetteer.cityAt(at)?.name }
        }
        // Tracks are in time order, so the journey's own are a slice rather than a filter — every
        // journey would otherwise walk the whole history to discard all but a few hours of it.
        val from = tracks.indexOfFirst { it.endedAt > travel.windowStart }
        if (from < 0) return
        for (index in from until tracks.size) {
            val track = tracks[index]
            if (track.startedAt >= travel.windowEnd) break
            val overlap = minOf(track.endedAt, travel.windowEnd) - maxOf(track.startedAt, travel.windowStart)
            if (overlap <= 0L) continue
            val name = nameAt(track.start) ?: continue
            if (name != nameAt(track.end)) continue
            timeByName[name] = (timeByName[name] ?: 0L) + overlap
        }
    }

    /**
     * What one stop on a journey is called: **the city it sits in**, except where the place is a
     * person's — their own home or someone else's ([PlaceCategoryGroup.HOME_PEOPLE]).
     *
     * This is the one surface where a user's own name for a place is *not* the better answer, which
     * is why the usual precedence — [PlaceResolver.ResolvedStay.name], which every row naming a
     * single stop goes through — is inverted here. A hotel is named after where someone slept, and a
     * journey is not to a hotel: it is to the city the hotel is in, and "Hotel Ibis" tells the reader
     * nothing they meant by going. A person's place is the exception because visiting them genuinely
     * is the destination — a week at a parent's reads better as their name than as the name of their
     * village.
     *
     * A label also sits wherever the recorder stopped, which for a car is the parking spot; recording
     * on foot scatters that one label into many small clusters with no big named place left to carry
     * a journey's name, while the city it is in stays the city it is in.
     */
    private inline fun nameOf(place: Place?, city: () -> String?): String? {
        // The city is asked for only when it can win — a person's place answers without one, and
        // resolving a coordinate is several walks of the gazetteer.
        if (place?.placeCategory?.group == PlaceCategoryGroup.HOME_PEOPLE) return place.label
        return city() ?: place?.label
    }

    /**
     * How long a place must hold a journey before it earns a place in its name. Below a couple of
     * hours it was passed through, not visited, and naming those turns every road trip's name into a
     * list of service stations. **Time spent in a place, not time stopped there** — a day walking a
     * city is mostly movement, and the caller counts a track that begins and ends in one place as
     * time in it.
     *
     * **An absolute floor, deliberately, rather than a share of the journey.** A share is hostage to
     * how the places around it happen to resolve: when two nearby stops fall to different names, each
     * piece's share halves and both can drop out while the journey clearly went there. Hours do not
     * move when the gazetteer does.
     */
    const val MIN_STAY_MS = 2 * 60 * 60 * 1000L

    /** Names listed in full up to here; beyond it the name lists two and counts the rest. */
    const val MAX_NAMES = 3

    /**
     * The places worth naming a journey after, most time first. [timeByName] is each name's total
     * stay time within the journey — already resolved, and already summed where two clusters share
     * a name, since a city someone slept in twice is one place they went.
     *
     * Ties keep the order they arrived in, which is chronological: two cities with the same hours
     * read in the order they were visited, and nothing about the answer changes between runs.
     */
    fun ranked(timeByName: Map<String, Long>): List<String> =
        timeByName.entries
            .filter { it.value >= MIN_STAY_MS }
            .sortedByDescending { it.value }
            .map { it.key }
            // A journey the recorder saw little of still went somewhere: where nothing clears the
            // floor, the longest stay names it rather than leaving the journey anonymous.
            .ifEmpty { listOfNotNull(timeByName.maxByOrNull { it.value }?.key) }

    /**
     * What to call a journey: [ranked]'s answer as one line, falling back to the plain fact of being
     * away. **The fallback is here, not on the screens** — what a journey with nothing to name it by
     * is called is a naming decision, and two screens deciding it apart is two names for one journey.
     * Which of the two it is, is the decision; the fallback's wording is the host's.
     */
    fun label(names: List<String>, nightCount: Int): TravelLabel =
        title(names)?.let(TravelLabel::Destinations) ?: TravelLabel.NightsAway(nightCount)

    /** [ranked]'s answer as one line, or null when nothing named the journey. */
    fun title(names: List<String>): String? = when {
        names.isEmpty() -> null
        names.size <= MAX_NAMES -> names.joinToString(" · ")
        // Two names and a count, rather than three and a smaller count: the row has to hold the
        // nights and the distance too, and a third name is what pushes it into eliding one of those.
        else -> names.take(2).joinToString(" · ") + " +${names.size - 2}"
    }
}
