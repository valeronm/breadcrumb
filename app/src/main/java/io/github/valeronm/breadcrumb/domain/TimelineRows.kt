package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.TrackSummary
import java.time.Instant
import java.time.ZoneId

/**
 * The timeline's rows, cut from what [StayDeriver] derived: intervals sliced at local midnights so
 * each piece files under one day, and those pieces merged with the tracks into one list. Nothing
 * here decides where anyone was — that is the derivation's — only how what it decided is laid out
 * by day.
 */
/** The clock each end of a span ran on — a track or an unrecorded absence can cross a border. */
data class Clocks(val start: ZoneId, val end: ZoneId) {
    companion object {
        /** Both ends on one clock — a stay sits in a single place. */
        fun both(zone: ZoneId) = Clocks(zone, zone)
    }
}

object TimelineRows {

    /**
     * Splits **stays** at local midnights so each piece falls inside one calendar day (a
     * 20:00–09:00 stay renders in both days with clamped bounds). An ongoing stay keeps its null
     * end on the final (today's) slice.
     *
     * **A gap is cut once and never per day**, and the asymmetry is the point: a stay says something
     * true about each day it covers — where someone was — so a day is entitled to its own piece of
     * one. A gap says nothing about the days in the middle of it, and cutting per day gave those days
     * a row holding no time, no place and no end to offer the trip form, whose whole content was that
     * nothing is known. Its two *ends* are real, though, and each belongs to its own day: one cut, at
     * the local midnight opening the day it ended, gives the departure day a row saying when
     * recording stopped and the arrival day one saying when it resumed. The days between are folded
     * into the departure half and get no rows, which is what an unrecorded day is; how long the
     * absence ran is then read off the two day headings, which is where a reader looks anyway.
     *
     * **[zonesOf] answers per interval, not once**, because a midnight is a fact about where someone
     * was and not about the device reading the history back: a stay abroad ends its day on that
     * country's clock. A caller that hands back one constant zone for everything gets the whole
     * history cut on that clock, which is the old behaviour exactly.
     *
     * It answers with the clocks the interval's **two ends** ran on, and they are usually the same
     * one — every stay sits in a single place, which is what makes its own midnights the right cut.
     *
     * Where they differ the interval is an unrecorded **crossing**, and the single cut matters for a
     * second reason: cutting it at either end's midnights throughout would file the same absence
     * under two headings, once on the calendar the departure's clock was keeping and once on the
     * arrival's, and no choice of which end to file by removes that.
     *
     * Every reader of a bound this produced must ask [zonesOf] the same question about the same
     * interval, or a row will contradict the day it was filed under.
     */
    fun slicePerDay(
        intervals: List<StayDeriver.Interval>,
        zonesOf: (StayDeriver.Interval) -> Clocks,
        nowMs: Long,
    ): List<Slice> =
        intervals.flatMap { interval ->
            val clocks = zonesOf(interval)
            // One rule per kind, which is the whole of the asymmetry this doc argues for. A stay's
            // two ends always answer with the same clock ([zonesOf]), so it never reaches the halving.
            when (interval) {
                is StayDeriver.Gap -> halvesAtArrivalDay(interval, clocks.start, clocks.end)
                is StayDeriver.Stay -> daySlicesOf(interval, clocks.start, nowMs)
            }
        }

    /**
     * An absence as its two halves — everything up to the arrival's local midnight, and the arrival's
     * own day. One piece when the whole of it already sits inside that day, which is the ordinary
     * short outage: there is no second day to give a row to, so one piece speaks for both ends.
     *
     * Whole days in the middle are folded into the departure half rather than marked one by one:
     * they hold neither end, and a row for one could only say that nothing is known.
     *
     * Each half is stamped with the end it speaks for and null for the other, at the point of
     * cutting — the only place that knows. A caller recovering it afterwards by comparing bounds
     * would be re-deciding what was decided here, and would have to be corrected in step with any
     * change to where the cut lands.
     */
    private fun halvesAtArrivalDay(gap: StayDeriver.Gap, departureZone: ZoneId, arrivalZone: ZoneId): List<Slice> {
        val arrivalDayStart = startOfDay(gap.end, arrivalZone)
        if (arrivalDayStart <= gap.start) return listOf(Slice(gap, departureZone, arrivalZone))
        return listOf(
            Slice(gap.copy(end = arrivalDayStart), departureZone, null, holdsEnd = false),
            Slice(gap.copy(start = arrivalDayStart), null, arrivalZone, holdsStart = false),
        )
    }

    /** A stay as one piece per calendar day it covers, on its own [zone]; an ongoing one keeps its
     *  null end on the final piece, which is today's. Each piece says which of its bounds are the
     *  stay's own and which the slicing put there — see [Slice.holdsStart]. */
    private fun daySlicesOf(stay: StayDeriver.Stay, zone: ZoneId, nowMs: Long): List<Slice> {
        val end = stay.end ?: nowMs
        val slices = mutableListOf<Slice>()
        var sliceStart = stay.start
        while (true) {
            val first = sliceStart == stay.start
            val nextMidnight = midnightAfter(sliceStart, zone)
            if (end <= nextMidnight) {
                slices += Slice(stay.copy(start = sliceStart), zone, zone, holdsStart = first)
                break
            }
            slices += Slice(
                stay.copy(start = sliceStart, end = nextMidnight),
                zone,
                zone,
                holdsStart = first,
                holdsEnd = false,
            )
            sliceStart = nextMidnight
        }
        return slices
    }

    /** Local midnight opening the day [epochMs] falls in. The one arithmetic here that DST rides
     *  on, so it is written once and [midnightAfter] is the only variant. */
    private fun startOfDay(epochMs: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate().atStartOfDay(zone)
            .toInstant().toEpochMilli()

    /** Local midnight closing the day [epochMs] falls in — see [startOfDay]. */
    private fun midnightAfter(epochMs: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone)
            .toInstant().toEpochMilli()

    /**
     * Merges the DESC track list with derived intervals into one DESC timeline. On a start-time
     * tie the interval sorts newer (it extends past the shared instant; the track ended at it).
     */
    fun interleave(summaries: List<TrackSummary>, slices: List<Slice>): List<TimelineItem> {
        val descSlices = slices.asReversed()
        val out = ArrayList<TimelineItem>(summaries.size + slices.size)
        var t = 0
        var v = 0
        while (t < summaries.size || v < descSlices.size) {
            val track = summaries.getOrNull(t)
            val interval = descSlices.getOrNull(v)?.interval
            val takeInterval = when {
                interval == null -> false
                track == null -> true
                interval.start != track.startedAt -> interval.start > track.startedAt
                // Start-time tie: an ongoing interval is the newest thing on the timeline, but a
                // closed one ended the instant this track began (a zero-length trim seam), so the
                // departing track is newer and the interval sorts between the two tracks.
                else -> interval.end == null
            }
            if (takeInterval) {
                val slice = descSlices[v++]
                out += when (val iv = slice.interval) {
                    // A stay sits in one place, so its two ends answer with the same clock and the
                    // slicer set both; either reads it.
                    is StayDeriver.Stay -> TimelineItem.StayItem(
                        iv,
                        zone = slice.departureZone,
                        holdsStart = slice.holdsStart,
                        holdsEnd = slice.holdsEnd,
                    )
                    is StayDeriver.Gap -> TimelineItem.GapItem(
                        iv,
                        departureZone = slice.departureZone,
                        arrivalZone = slice.arrivalZone,
                        holdsDeparture = slice.holdsStart,
                        holdsArrival = slice.holdsEnd,
                    )
                }
            } else {
                out += TimelineItem.TrackItem(summaries[t++])
            }
        }
        return out
    }

    /**
     * One row's worth of an interval, with the clock each of its ends runs on.
     *
     * **Null means this piece does not speak for that end** — a crossing's departure half carries no
     * arrival clock, because the arrival is a row of its own under its own day's heading. Everything
     * that happens in one place carries the same clock twice.
     */
    data class Slice(
        val interval: StayDeriver.Interval,
        val departureZone: ZoneId?,
        val arrivalZone: ZoneId?,
        /**
         * Whether this piece's own bounds are the interval's own, or boundaries the day slicing put
         * there — **stamped here, at the only point that knows**, and read by both kinds of row.
         *
         * A reader deciding it instead by comparing a bound against local midnight cannot tell an
         * interval that genuinely begins at 00:00 from one cut there, and the add-trip form produces
         * exactly that: its pickers resolve to the minute, so a trip typed to arrive at midnight
         * lands on the boundary and the stay after it would lose its start time and its duration.
         */
        val holdsStart: Boolean = true,
        val holdsEnd: Boolean = true,
    )
}

/** One row of the day-grouped timeline: a recorded track, a derived stay, or a data gap. */
sealed interface TimelineItem {
    val startedAt: Long

    /**
     * The instant deciding which day this row is filed under — its start, and its start for
     * everything that happens in one place. A row spanning two calendars overrides it; see
     * [GapItem.filedAt] for why an arrival wins over a departure there.
     *
     * Sorting is still by [startedAt]: this moves a row's *heading*, never its position.
     */
    val filedAt: Long get() = startedAt

    /**
     * The clock this row ran on — the zone of the place it happened in, attached after derivation
     * beside the place resolution; null only if attachment wasn't run.
     *
     * **It must be the zone [TimelineRows.slicePerDay] cut this row's bounds in.** A bound clamped
     * to one midnight and read back against another is a row contradicting the day it is filed
     * under, which is the whole reason the zone rides the row rather than being looked up per
     * reader.
     */
    val zone: ZoneId?

    data class TrackItem(
        val summary: TrackSummary,
        /** Where it set off — the clock it is filed and starts on. */
        override val zone: ZoneId? = null,
        /**
         * Where it arrived. **A track is the one recorded row that can cross a border** — a stay
         * sits in one place by definition — so its two ends are read on their own clocks and the
         * crossing shows, rather than being flattened onto the departure's and hidden.
         *
         * Null means *unattached*, exactly as [zone] does, and never "the same as [zone]": a track
         * that began and ended on one clock carries that clock twice. Encoding sameness as null
         * would give the word a second meaning here that it does not have on [GapItem], forty lines
         * down, where null means the row does not speak for that end at all.
         */
        val endZone: ZoneId? = null,
    ) : TimelineItem {
        override val startedAt get() = summary.startedAt
    }

    data class StayItem(
        val stay: StayDeriver.Stay,
        /** Place resolution, attached after derivation; null only if resolution wasn't run. */
        val place: PlaceResolver.ResolvedStay? = null,
        /** Non-null when this short same-activity stay can be closed by merging its two tracks. */
        val merge: TrackMerge.Plan? = null,
        override val zone: ZoneId? = null,
        /**
         * Whether this row's bounds are the stay's own or seams the day slicing put there — see
         * [TimelineRows.Slice.holdsStart], which stamps them. A row states only the bounds it holds,
         * and only a row holding both can say how long the stay lasted.
         */
        val holdsStart: Boolean = true,
        val holdsEnd: Boolean = true,
    ) : TimelineItem {
        override val startedAt get() = stay.start

        /**
         * A row about a *join* rather than about being anywhere: two tracks sharing an instant leave
         * a stay of no duration between them — a split's cut, or a hand-entered trip landing exactly
         * on the absence it fills — and this one has nothing to say about it.
         *
         * Such a seam earns its row only while it carries the offer to undo the join, which is why
         * this asks after [merge] rather than after the writers either side: refusing across writers
         * is one of three things [TrackMerge.plan] refuses on, and a seam whose offer was refused for
         * either of the others says exactly as little.
         *
         * The derivation still emits every seam — this is the timeline deciding what to draw, and
         * the readers below it decide separately. [PlaceResolver] asks its own, narrower question of
         * the same intervals (was anyone anywhere, rather than is this row worth a line), so a seam
         * counts as no visit there whether or not a merge is offered here.
         */
        val isBareSeam: Boolean get() = merge == null && stay.hasNoDuration
    }

    data class GapItem(
        val gap: StayDeriver.Gap,
        /** Place resolution of each known side, attached after derivation — lets the row link
         *  through to the places whose misclustering usually caused the gap. */
        val fromPlace: PlaceResolver.ResolvedStay? = null,
        val toPlace: PlaceResolver.ResolvedStay? = null,
        /** Non-null when this short same-activity gap can be closed by merging its two tracks. */
        val merge: TrackMerge.Plan? = null,
        /**
         * The clocks this row's two ends run on, **null where the row does not speak for that end**
         * — see [TimelineRows.Slice], which sets them.
         *
         * Any absence longer than the day it ended on is cut into the day it left and the day it
         * landed, one end each. Three states, and every one is read off these two: a row holding
         * both ends carries a clock twice (the absence began and ended on one day); a departure half
         * carries no arrival clock; an arrival half no departure clock. Both null is the state
         * nothing produces — see [holdsDeparture].
         */
        val departureZone: ZoneId? = null,
        val arrivalZone: ZoneId? = null,
        /**
         * Whether this row states the absence's real start — and [holdsArrival] its real end. **The
         * two questions the whole card is drawn from**, stamped by the slicer when it cut
         * ([TimelineRows.Slice.holdsStart]) and read here; everything else about the row follows.
         *
         * At least one is always true: a piece holding neither would be a day the absence merely
         * passes through, and those are folded into the departure half rather than given a row.
         */
        val holdsDeparture: Boolean = true,
        /** See [holdsDeparture]. */
        val holdsArrival: Boolean = true,
    ) : TimelineItem {
        override val startedAt get() = gap.start

        /** The clock the row is read and filed on: the end it arrives at, or its departure where it
         *  speaks for no arrival. */
        override val zone get() = if (holdsArrival) arrivalZone else departureZone

        /** True where the two ends run on genuinely different clocks. Only a row holding *both* can
         *  say so, which after the cut means an absence that ended on the day it began — a hop, and
         *  the only row that has to state two times of its own. */
        val spansClocks get() = holdsDeparture &&
            holdsArrival &&
            departureZone != arrivalZone

        /**
         * **An arrival half is filed under the day it landed**; everything else by where it began,
         * like every other row.
         *
         * The row following an arrival is whatever was recorded on getting there — the taxi from the
         * airport — and it belongs to the arrival's day. File the arrival half by where it *began*
         * and a heading lands between the two, leaving an arrival at 19:16 under yesterday while the
         * 19:16 taxi sits under today. Nothing can sort between a gap's ends, there being no
         * recording in it, so this moves a heading and never the order.
         *
         * A row holding the departure must **not** take this: it would file an absence under the day
         * it finished and leave the day it began showing nothing at all.
         *
         * The cut currently puts an arrival half's start *at* that day's midnight, so the two agree
         * and this is belt and braces. It says which end the row is filed by, and goes on saying it
         * if the cut ever lands elsewhere.
         */
        override val filedAt get() = if (holdsArrival && !holdsDeparture) gap.end else gap.start
    }
}
