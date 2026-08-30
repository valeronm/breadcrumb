package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.TrackSummary

/**
 * How far one activity carried the user over a stretch of timeline, and how long it took.
 *
 * Keyed by the **stored** `activityType` string rather than by [type], because a code this build
 * cannot read is still a distance, and merging every such code into one bucket would add together
 * things that were never the same. [type] is that code read back, resolved once here so no caller
 * has to repeat it per row and per frame; it is null for a code this build predates, which the UI
 * names from the raw string instead.
 */
class ActivityTotal(val activityType: String, val meters: Double, val durationMs: Long) {
    val type: ActivityType? = ActivityType.ofName(activityType)
}

/** How long a stretch of timeline went to places of one category, and over how many visits. */
class CategoryTotal(val category: PlaceCategory, val durationMs: Long, val visits: Int)

/**
 * What a stretch of the timeline came to, both ways: the movement in it, and the time it spent at
 * each kind of place. What stretch — a day, a month, whatever a caller is bucketing by — is the
 * caller's, which is the whole reason this is one rule rather than one per scale.
 */
class TimelineTotals(
    val activities: Map<String, ActivityTotal>,
    val categories: Map<PlaceCategory, CategoryTotal>,
) {
    /**
     * Nothing here to report. A stretch can hold rows and still be empty — stays at untagged places,
     * or at categories [PlaceCategory.inTimeTotals] excludes, contribute to neither map. Named
     * rather than spelled as a two-clause predicate at each caller, which would silently stop
     * covering a third figure the day one is added.
     */
    val isEmpty: Boolean get() = activities.isEmpty() && categories.isEmpty()

    companion object {
        val EMPTY = TimelineTotals(emptyMap(), emptyMap())
    }
}

/**
 * A [TimelineTotals] under construction — one per bucket, fed row by row.
 *
 * A track may also go in as a bare [TrackSummary], which is all its contribution reads: that is what
 * lets a caller holding only summaries (the Record tab's periods) ask the same question as one
 * walking the timeline.
 */
class TimelineTotalsBuilder {

    /** One activity's running figures. Mutable fields rather than a pair of maps merged by key: a
     *  row then hashes its activity once, and nothing has to hold the two maps' keysets equal. */
    private class Moving {
        var meters = 0.0
        var durationMs = 0L
    }

    private class Staying {
        var durationMs = 0L

        /**
         * The **intervals** behind the stay rows, never the rows: the timeline arrives sliced per
         * day, so a fortnight at one hotel is fourteen rows and one visit. A slice keeps the
         * `afterTrackId` of the interval it was cut from, which is what identifies that visit — and
         * a stay running over a bucket's boundary is then one visit in each, which it was.
         *
         * A set rather than a counter bumped when the id changes, though the slices of one interval
         * do arrive together: that adjacency is [StayDeriver]'s to keep, not this file's to lean on.
         */
        val visits = HashSet<Long>()
    }

    private val moving = HashMap<String, Moving>()
    private val staying = HashMap<PlaceCategory, Staying>()

    val isEmpty: Boolean get() = moving.isEmpty() && staying.isEmpty()

    /** A track still recording runs to [nowMs], as an open stay does. */
    fun add(track: TrackSummary, nowMs: Long) {
        val acc = moving.getOrPut(track.activityType) { Moving() }
        acc.meters += track.distanceMeters
        acc.durationMs += (track.endedAt ?: nowMs) - track.startedAt
    }

    /**
     * What one row contributes — **the single place that says**, so a row type added later cannot be
     * handled one way here and another way by a caller doing its own dispatch. A gap contributes
     * nothing: it is time in which nothing was recorded.
     */
    fun add(item: TimelineItem, nowMs: Long) {
        when (item) {
            is TimelineItem.TrackItem -> add(item.summary, nowMs)
            is TimelineItem.StayItem -> addStay(item, nowMs)
            is TimelineItem.GapItem -> Unit
        }
    }

    fun addAll(items: List<TimelineItem>, nowMs: Long) {
        for (item in items) add(item, nowMs)
    }

    /**
     * A stay counts toward its place's category, if it has one worth totalling: untagged and unnamed
     * stops contribute nothing — there is nothing to attribute them to — nor do the categories
     * [PlaceCategory.inTimeTotals] excludes. An open stay runs to [nowMs].
     */
    private fun addStay(item: TimelineItem.StayItem, nowMs: Long) {
        val category = item.place?.category?.takeIf { it.inTimeTotals } ?: return
        val acc = staying.getOrPut(category) { Staying() }
        acc.durationMs += (item.stay.end ?: nowMs) - item.stay.start
        acc.visits.add(item.stay.afterTrackId)
    }

    fun build() = TimelineTotals(
        activities = moving.mapValues { (type, acc) -> ActivityTotal(type, acc.meters, acc.durationMs) },
        categories = staying.mapValues { (category, acc) ->
            CategoryTotal(category, acc.durationMs, acc.visits.size)
        },
    )
}

/**
 * Distance per activity over some tracks, furthest first — a timeline day header's top line, and the
 * Record tab's per-period summary. Takes summaries rather than rows because that is all it reads,
 * and one of its two callers has nothing else.
 *
 * **No floor here, deliberately**, where the category line below has one: a trip is an entity the
 * reader owns and can delete, so a metre of GPS jitter that earned a row is theirs to remove. A stay
 * is derived — nothing creates or destroys one directly, and the only lever on a short one is
 * merging the trips around it, which [TrackMerge.plan] refuses across differing activity or writer.
 * A floor compensates for the missing control, so it belongs only where the control is missing.
 */
fun activityTotals(tracks: List<TrackSummary>, nowMs: Long): List<ActivityTotal> {
    val builder = TimelineTotalsBuilder()
    for (track in tracks) builder.add(track, nowMs)
    return builder.build().activities.values.sortedByDescending { it.meters }
}

/**
 * A category the day gave less than this to doesn't earn a place in its totals: the line answers
 * what the day went to, and a chip is as wide whether it reports six hours or two minutes, so the
 * short ones cost the reading exactly what the long ones do while saying nothing about the day's
 * shape. The floor is on the **summed** total, not on any one stop — several brief errands still
 * add up to an errand-shaped afternoon, which is the case that would be wrong to hide.
 *
 * **Not a sibling of [TrackMerge.MAX_INTERVAL_MS], which happens to hold the same number.** That one
 * bounds a *single* stay, being how long a stop may be and still be absorbed by merging the trips
 * either side; this bounds a *category's whole day*. Three mergeable two-minute stops clear this
 * floor together and are meant to. Tying the two would silently make one quantity the other.
 */
private const val REPORTED_TOTAL_FLOOR_MS = 5 * 60_000L

/**
 * Time per category over one day's stays, longest first — what a timeline day header reports under
 * the distances. The stays arrive already sliced at midnight ([TimelineRows.slicePerDay]), so a
 * stay's bounds here are its share of *this* day and summing them is the day's total. Two rules
 * differ from a stay row's on purpose — the difference between describing one stop and a day: a
 * midnight-sliced bound doesn't suppress the duration (the row hides one because it would merely
 * restate its own clock times, while the total asks how much of the day went here — a night at
 * home is the day's hours from midnight), and a stop too short to quote on its own row still
 * counts toward its category (so no [StayDeriver.Stay.reportableDurationMs] floor on the
 * stays). What is dropped is a *category* under [REPORTED_TOTAL_FLOOR_MS], once summed — so these
 * totals deliberately don't add up to the day, and are a summary rather than a ledger.
 *
 * The floor is this function's alone, and the reason the day and the month are separate entry points
 * onto one rule: a month is the scale at which a handful of short errands is exactly the thing worth
 * seeing (see `MonthlyTotals`, which applies none).
 */
fun dayCategoryTotals(items: List<TimelineItem>, nowMs: Long): List<CategoryTotal> =
    TimelineTotalsBuilder().apply { addAll(items, nowMs) }.build().categories.values
        .filter { it.durationMs >= REPORTED_TOTAL_FLOOR_MS }
        .sortedByDescending { it.durationMs }

/** A journey's two tables, each sorted by its own measure — see [journeyTotals]. */
class JourneyTotals(val activities: List<ActivityTotal>, val categories: List<CategoryTotal>)

/**
 * A journey's totals — activities furthest first, categories longest first, and **no category
 * floor, deliberately**, where the day applies one: the day's line is a strip of chips where a
 * two-minute category costs the reading what a six-hour one does, while the journey page gives
 * every category a row with room for its figures — and a journey is exactly the reading where a
 * brief stop is still worth a line. The third scale beside [dayCategoryTotals] and
 * `MonthlyTotals`, all three summing through [TimelineTotalsBuilder].
 */
fun journeyTotals(items: List<TimelineItem>, nowMs: Long): JourneyTotals {
    val totals = TimelineTotalsBuilder().apply { addAll(items, nowMs) }.build()
    return JourneyTotals(
        activities = totals.activities.values.sortedByDescending { it.meters },
        categories = totals.categories.values.sortedByDescending { it.durationMs },
    )
}
