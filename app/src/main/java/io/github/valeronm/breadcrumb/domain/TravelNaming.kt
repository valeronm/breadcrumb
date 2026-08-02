package io.github.valeronm.breadcrumb.domain

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
object TravelNaming {

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
     */
    fun label(names: List<String>, nightCount: Int): String =
        title(names) ?: if (nightCount == 1) "1 night away" else "$nightCount nights away"

    /** [ranked]'s answer as one line, or null when nothing named the journey. */
    fun title(names: List<String>): String? = when {
        names.isEmpty() -> null
        names.size <= MAX_NAMES -> names.joinToString(" · ")
        // Two names and a count, rather than three and a smaller count: the row has to hold the
        // nights and the distance too, and a third name is what pushes it into eliding one of those.
        else -> names.take(2).joinToString(" · ") + " +${names.size - 2}"
    }
}
