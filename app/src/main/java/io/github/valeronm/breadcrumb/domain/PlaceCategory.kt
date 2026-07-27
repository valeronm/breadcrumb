package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.Place

/**
 * A coarser reading of [PlaceCategory] — the handful of *kinds* of stop a category belongs to, which
 * is what the colour coding shows. A colour per category would be a legend to memorize; five are a
 * pattern you pick up by scrolling, and a day's stays read as a shape before any label is read.
 *
 * Groups are for display only: they never decide what is stored, tagged or totalled. That is why the
 * transient pair being its own group and being the pair outside time totals is a coincidence worth
 * noting rather than a rule to lean on — [PlaceCategory.inTimeTotals] stays the authority.
 *
 * [label] is user-facing — it heads each run of the category picker — so it reads as a heading rather
 * than as the design term the entry is named for.
 */
enum class PlaceCategoryGroup(val label: String) {
    HOME_PEOPLE("Home & people"),
    ERRANDS("Errands"),
    ROUTINE("Routine"),
    AWAY("Away"),
    TRANSIENT("Passing through"),
}

/**
 * What a place is *for* — the category a user assigns to a place, which the timeline reads back as
 * the glyph and label on a stay.
 *
 * [code] is the stable string stored in the `places.category` column, and null there means
 * **untagged** — a first-class state rather than one more category. A place nothing fits stays blank
 * and shows no chip, exactly like a stay at an unnamed cluster, rather than landing in an "Other"
 * bucket that would collect precisely the places worth finding again while saying nothing about them.
 *
 * The set is closed and not user-extensible: per-category totals only compare over a vocabulary
 * that doesn't drift between devices and backups, and each entry owes an icon (see the UI layer's
 * mapping — an `ImageVector` can't live in this package) and a [group] to be colored by.
 *
 * **Codes are permanent, labels are not.** A code is written to the DB and to the backup format the
 * web viewer reads; [label] is display text, free to reword without touching stored data.
 *
 * **Declared in the order the picker shows them**: by [group], and within a group by how often the
 * category is *chosen* — which is how many places fall into it, not how much time is spent at them.
 * Those pull in opposite directions at both ends: [HOME] and [WORK] take the most hours of anyone's
 * week and are picked once each, since most people have one of either, so they sit at the back of
 * their groups; groceries and food are picked over and over, a different shop each time.
 */
enum class PlaceCategory(
    val code: String,
    val label: String,
    /** The colour family this category reads as — see [PlaceCategoryGroup]. */
    val group: PlaceCategoryGroup,
    /**
     * Whether this category earns a line in a time total. [HOME] is out because it is the baseline a
     * day returns to, and at eight hours a night it would dwarf every other line beside it.
     * [PARKING] and [GAS_STATION] are out because they are *transient* — somewhere passed through on
     * the way, with no purpose of their own to spend a day's hours on, which is exactly what a total
     * reports. No exclusion touches tagging or the stay row: a car park is still worth pinning, and
     * its stay still says what it is. This is only about which totals are worth reading.
     */
    val inTimeTotals: Boolean = true,
) {
    // Home & people
    FRIENDS_FAMILY("friends_family", "Friends & family", PlaceCategoryGroup.HOME_PEOPLE),
    HOME("home", "Home", PlaceCategoryGroup.HOME_PEOPLE, inTimeTotals = false),

    // Errands
    GROCERIES("groceries", "Groceries", PlaceCategoryGroup.ERRANDS),
    FOOD("food", "Food & drink", PlaceCategoryGroup.ERRANDS),
    SHOPPING("shopping", "Shopping", PlaceCategoryGroup.ERRANDS),
    SERVICES("services", "Services", PlaceCategoryGroup.ERRANDS),

    /** Doctor, dentist, hospital. Split from [SERVICES], which is where the car and the paperwork
     *  go: an appointment is not an errand, even when the trip to it looks like one. */
    HEALTH("health", "Health", PlaceCategoryGroup.ERRANDS),

    // Routine
    KIDS_SCHOOL("kids_school", "Kids & school", PlaceCategoryGroup.ROUTINE),
    SPORTS("sports", "Sports & fitness", PlaceCategoryGroup.ROUTINE),
    WORK("work", "Work", PlaceCategoryGroup.ROUTINE),

    // Away
    OUTDOORS("outdoors", "Outdoors", PlaceCategoryGroup.AWAY),
    SIGHTSEEING("sightseeing", "Sightseeing", PlaceCategoryGroup.AWAY),
    TRAVEL("travel", "Travel", PlaceCategoryGroup.AWAY),

    /** Cinema, pool, bowling, waterpark, play centre — somewhere you buy your way in for the
     *  afternoon. Grouped with the other leisure categories rather than with [KIDS_SCHOOL], which is
     *  the week's obligations: a cinema trip is not a school run, with or without children. */
    ENTERTAINMENT("entertainment", "Entertainment", PlaceCategoryGroup.AWAY),

    // Passing through
    PARKING("parking", "Parking", PlaceCategoryGroup.TRANSIENT, inTimeTotals = false),

    /** Filling up. Split from [SERVICES] because a fuel stop is an errand's punctuation, not the
     *  errand — and like [PARKING], time there is passed through rather than spent. */
    GAS_STATION("gas_station", "Gas station", PlaceCategoryGroup.TRANSIENT, inTimeTotals = false),
    ;

    companion object {
        /**
         * The category for a stored code — null for an untagged place, and null too for a code this
         * build doesn't know, which reads as untagged rather than failing. That tolerance is what
         * lets a backup written by a later version restore here: the column keeps the raw string,
         * so the code survives the round trip even while this build can't name it.
         */
        fun fromCode(code: String?): PlaceCategory? = entries.firstOrNull { it.code == code }
    }
}

/** This place's category, or null when it is untagged (or tagged with a code this build predates). */
val Place.placeCategory: PlaceCategory? get() = PlaceCategory.fromCode(category)
