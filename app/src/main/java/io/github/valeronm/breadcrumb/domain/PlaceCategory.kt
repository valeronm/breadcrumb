package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.Place

/**
 * A coarser reading of [PlaceCategory] — the handful of *kinds* of stop, which is what the colour
 * coding shows: a colour per category would be a legend to memorize, five are a pattern picked up
 * by scrolling, and a day's stays read as a shape before any label is read. Display only — groups
 * never decide what is stored, tagged or totalled, so the transient pair being its own group *and*
 * the pair outside time totals is coincidence, not a rule to lean on: [PlaceCategory.inTimeTotals]
 * stays the authority. Each group heads a run of the category picker, and the heading it reads as —
 * a heading rather than the design term the entry is named for — is `PlaceCategoryGroup.labelRes`
 * in the UI layer, where language belongs.
 */
enum class PlaceCategoryGroup {
    HOME_PEOPLE,
    ERRANDS,
    ROUTINE,
    AWAY,
    TRANSIENT,
}

/**
 * What a place is *for* — the category a user assigns, read back by the timeline as the glyph and
 * label on a stay. [code] is the stable string in the `places.category` column; null there means
 * **untagged**, a first-class state rather than one more category — a place nothing fits stays
 * blank and shows no chip, like a stay at an unnamed cluster, rather than landing in an "Other"
 * bucket that would collect precisely the places worth finding again while saying nothing about
 * them. The set is closed and not user-extensible: per-category totals only compare over a
 * vocabulary that doesn't drift between devices and backups, and each entry owes an icon and a name
 * (both in the UI layer — an `ImageVector` can't live in this package, and a name is language) plus
 * a [group] to be colored by. **Codes are permanent, names are not**: a code is written to the DB
 * and the backup format the web viewer reads, while `PlaceCategory.labelRes` is display text, free
 * to reword and to translate. **Declared in picker order**: by [group],
 * and within a group by how often the category is *chosen* — how many places fall into it, not
 * time spent at them. Those pull opposite ways at both ends: [HOME] and [WORK] take the most hours
 * of anyone's week yet are picked once each (most people have one of either), so they sit at the
 * back of their groups; groceries and food are picked over and over, a different shop each time.
 */
enum class PlaceCategory(
    val code: String,
    /** The colour family this category reads as — see [PlaceCategoryGroup]. */
    val group: PlaceCategoryGroup,
    /**
     * Whether this category earns a line in a time total. [HOME] is out as the baseline a day
     * returns to — at eight hours a night it would dwarf every other line. [PARKING] and
     * [GAS_STATION] are out as *transient*: passed through on the way, no purpose of their own to
     * spend a day's hours on — exactly what a total reports. Neither exclusion touches tagging or
     * the stay row: a car park is still worth pinning, and its stay still says what it is.
     */
    val inTimeTotals: Boolean = true,
    /**
     * Whether a stop here counts as somewhere a journey went. A fuel stop and a motorway service
     * area are the road itself, not a place on it: they would name journeys after villages nobody
     * saw and count them as cities visited. [PARKING] deliberately still counts — for a history
     * recorded by car, the car park is the evidence of being in the city it sits in.
     */
    val visited: Boolean = true,
) {
    // Home & people
    FRIENDS_FAMILY("friends_family", PlaceCategoryGroup.HOME_PEOPLE),
    HOME("home", PlaceCategoryGroup.HOME_PEOPLE, inTimeTotals = false),

    // Errands
    GROCERIES("groceries", PlaceCategoryGroup.ERRANDS),
    FOOD("food", PlaceCategoryGroup.ERRANDS),
    SHOPPING("shopping", PlaceCategoryGroup.ERRANDS),
    SERVICES("services", PlaceCategoryGroup.ERRANDS),

    /** Doctor, dentist, hospital. Split from [SERVICES], which is where the car and the paperwork
     *  go: an appointment is not an errand, even when the trip to it looks like one. */
    HEALTH("health", PlaceCategoryGroup.ERRANDS),

    // Routine
    KIDS_SCHOOL("kids_school", PlaceCategoryGroup.ROUTINE),
    SPORTS("sports", PlaceCategoryGroup.ROUTINE),
    WORK("work", PlaceCategoryGroup.ROUTINE),

    // Away
    OUTDOORS("outdoors", PlaceCategoryGroup.AWAY),
    SIGHTSEEING("sightseeing", PlaceCategoryGroup.AWAY),
    TRAVEL("travel", PlaceCategoryGroup.AWAY),

    /** Cinema, pool, bowling, waterpark, play centre — somewhere you buy your way in for the
     *  afternoon. Grouped with the other leisure categories rather than with [KIDS_SCHOOL], which is
     *  the week's obligations: a cinema trip is not a school run, with or without children. */
    ENTERTAINMENT("entertainment", PlaceCategoryGroup.AWAY),

    // Passing through
    PARKING("parking", PlaceCategoryGroup.TRANSIENT, inTimeTotals = false),

    /** A subway platform, a bus stop, a railway station — the network's own furniture. Time there
     *  is waiting rather than spent, and unlike [PARKING] it is no evidence of a visit either: a
     *  journey must not be named after the interchange it changed trains at. */
    TRANSIT("transit", PlaceCategoryGroup.TRANSIENT, inTimeTotals = false, visited = false),

    /** Filling up. Split from [SERVICES] because a fuel stop is an errand's punctuation, not the
     *  errand — and like [PARKING], time there is passed through rather than spent. */
    GAS_STATION("gas_station", PlaceCategoryGroup.TRANSIENT, inTimeTotals = false, visited = false),

    /** The motorway stop with the fuel, the coffee and the car park together. Split from
     *  [GAS_STATION] because it holds hours rather than minutes and would otherwise name the
     *  journey passing it. */
    SERVICE_AREA("service_area", PlaceCategoryGroup.TRANSIENT, inTimeTotals = false, visited = false),
    ;

    companion object {
        /**
         * The category for a stored code — null for an untagged place, and null too for a code
         * this build doesn't know, which reads as untagged rather than failing: the column keeps
         * the raw string, so a later version's backup restores here with the code surviving the
         * round trip even while this build can't name it.
         */
        fun fromCode(code: String?): PlaceCategory? = entries.firstOrNull { it.code == code }
    }
}

/** This place's category, or null when it is untagged (or tagged with a code this build predates). */
val Place.placeCategory: PlaceCategory? get() = PlaceCategory.fromCode(category)
